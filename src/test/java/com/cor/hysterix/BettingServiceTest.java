package com.cor.hysterix;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Future;

import org.junit.Before;
import org.junit.Test;

import rx.Observable;

import com.cor.hysterix.command.CommandGetTodaysRaces;
import com.cor.hysterix.command.batching.CommandCollapserGetOddsForHorse;
import com.cor.hysterix.command.batching.GetOddsForHorseRequest;
import com.cor.hysterix.command.caching.CommandGetHorsesInRaceWithCaching;
import com.cor.hysterix.domain.Horse;
import com.cor.hysterix.domain.Racecourse;
import com.cor.hysterix.exception.RemoteServiceException;
import com.cor.hysterix.service.BettingService;
import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixRequestCache;
import com.netflix.hystrix.HystrixRequestLog;
import com.netflix.hystrix.exception.HystrixRuntimeException;
import com.netflix.hystrix.strategy.concurrency.HystrixConcurrencyStrategyDefault;
import com.netflix.hystrix.strategy.concurrency.HystrixRequestContext;

/**
 * Unit Test Suite that illustrates various approaches in using Hystrix Commands to access remote services.
 * 
 * Uses Mockito to wire in a mock remote betting service.
 *
 */
public class BettingServiceTest {

	private static final String RACE_1 = "course_aintree";
	private static final String HORSE_1 = "horse_redrum";
	private static final String HORSE_2 = "horse_shergar";

	private static final String ODDS_RACE_1_HORSE_1 = "10/1";
	private static final String ODDS_RACE_1_HORSE_2 = "100/1";

	private static final HystrixCommandKey GETTER_KEY = HystrixCommandKey.Factory.asKey("GetterCommand");

	private BettingService mockService;

	/**
	 * Set up the shared Unit Test environment
	 */
	@Before
	public void setUp() {
		mockService = mock(BettingService.class);
		when(mockService.getTodaysRaces()).thenReturn(getRaceCourses());
		when(mockService.getHorsesInRace(RACE_1)).thenReturn(getHorsesAtAintree());
		when(mockService.getOddsForHorse(RACE_1, HORSE_1)).thenReturn(ODDS_RACE_1_HORSE_1);
		when(mockService.getOddsForHorse(RACE_1, HORSE_2)).thenReturn(ODDS_RACE_1_HORSE_2);
	}

	/**
	 * Command GetRaces - Execute (synchronous call).
	 */
	@Test
	public void testSynchronous() {
		
		CommandGetTodaysRaces commandGetRaces = new CommandGetTodaysRaces(mockService);
		assertEquals(getRaceCourses(), commandGetRaces.execute());
		
		verify(mockService).getTodaysRaces();
		verifyNoMoreInteractions(mockService);
	}

	/**
	 * Command GetRaces - Execute and Fail Silently. 
	 * Swallows remote server error and returns an empty list.
	 */
	@Test
	public void testSynchronousFailSilently() {
		
		CommandGetTodaysRaces commandGetRacesFailure = new CommandGetTodaysRaces(mockService);
		// override mock to mimic an error being thrown for this test
		when(mockService.getTodaysRaces()).thenThrow(new RuntimeException("Error!!"));
		assertEquals(new ArrayList<Racecourse>(), commandGetRacesFailure.execute());
		
		// Verify 
		verify(mockService).getTodaysRaces();
		verifyNoMoreInteractions(mockService);
	}
	
	/**
	 * Command GetRaces - Execute and Fail Fast. 
	 * Catches remote server error and throws a new Exception.
	 */
	@Test
	public void testSynchronousFailFast() {
		CommandGetTodaysRaces commandGetRacesFailure = new CommandGetTodaysRaces(mockService, false);
		// override mock to mimic an error being thrown for this test
		when(mockService.getTodaysRaces()).thenThrow(new RuntimeException("Error!!"));
		try{
			commandGetRacesFailure.execute();
		}catch(HystrixRuntimeException hre){
			assertEquals(RemoteServiceException.class, hre.getFallbackException().getClass());
		}
		
		verify(mockService).getTodaysRaces();
		verifyNoMoreInteractions(mockService);
	}

	/**
	 * Command GetRaces - Queue (Asynchronous)
	 */
	@Test
	public void testAsynchronous() throws Exception {
		CommandGetTodaysRaces commandGetRaces = new CommandGetTodaysRaces(mockService);
		Future<List<Racecourse>> future = commandGetRaces.queue();
		assertEquals(getRaceCourses(), future.get());
		
		verify(mockService).getTodaysRaces();
		verifyNoMoreInteractions(mockService);
	}

	/**
	 * Command - Observe (Hot Observable)
	 */
	@Test
	public void testObservable() throws Exception {
		CommandGetTodaysRaces commandGetRaces = new CommandGetTodaysRaces(mockService);
		Observable<List<Racecourse>> observable = commandGetRaces.observe();
		// blocking observable
		assertEquals(getRaceCourses(), observable.toBlocking().single());
		
		verify(mockService).getTodaysRaces();
		verifyNoMoreInteractions(mockService);
	}

	/**
	 * Test - GetHorsesInRace - Uses Caching
	 */
	@Test
	public void testWithCacheHits() {
		
		HystrixRequestContext context = HystrixRequestContext.initializeContext();
		
		try {
			CommandGetHorsesInRaceWithCaching commandFirst = new CommandGetHorsesInRaceWithCaching(mockService, RACE_1);
			CommandGetHorsesInRaceWithCaching commandSecond = new CommandGetHorsesInRaceWithCaching(mockService, RACE_1);

			commandFirst.execute();
			// this is the first time we've executed this command with
			// the value of "2" so it should not be from cache
			assertFalse(commandFirst.isResponseFromCache());

			verify(mockService).getHorsesInRace(RACE_1);
			verifyNoMoreInteractions(mockService);

			commandSecond.execute();
			// this is the second time we've executed this command with
			// the same value so it should return from cache
			assertTrue(commandSecond.isResponseFromCache());

		} finally {
			context.shutdown();
		}

		// start a new request context
		context = HystrixRequestContext.initializeContext();
		try {
			CommandGetHorsesInRaceWithCaching commandThree = new CommandGetHorsesInRaceWithCaching(mockService, RACE_1);
			commandThree.execute();
			// this is a new request context so this
			// should not come from cache
			assertFalse(commandThree.isResponseFromCache());

			// Flush the cache
			HystrixRequestCache.getInstance(GETTER_KEY, HystrixConcurrencyStrategyDefault.getInstance()).clear(RACE_1);

		} finally {
			context.shutdown();
		}
	}

	/**
	 * Request Collapsing
	 */
	@SuppressWarnings("deprecation")
	@Test
	public void testCollapser() throws Exception {
		
		
		HystrixRequestContext context = HystrixRequestContext.initializeContext();

		CommandCollapserGetOddsForHorse c1 = new CommandCollapserGetOddsForHorse(new GetOddsForHorseRequest(RACE_1,
				HORSE_1));
		CommandCollapserGetOddsForHorse c2 = new CommandCollapserGetOddsForHorse(new GetOddsForHorseRequest(RACE_1,
				HORSE_1));
		CommandCollapserGetOddsForHorse c3 = new CommandCollapserGetOddsForHorse(new GetOddsForHorseRequest(RACE_1,
				HORSE_1));
		CommandCollapserGetOddsForHorse c4 = new CommandCollapserGetOddsForHorse(new GetOddsForHorseRequest(RACE_1,
				HORSE_1));

		c1.setService(mockService);
		c2.setService(mockService);
		c3.setService(mockService);
		c4.setService(mockService);

		try {
			Future<String> f1 = c1.queue();
			Future<String> f2 = c2.queue();
			Future<String> f3 = c3.queue();
			Future<String> f4 = c4.queue();

			assertEquals(ODDS_RACE_1_HORSE_1, f1.get());
			assertEquals(ODDS_RACE_1_HORSE_1, f2.get());
			assertEquals(ODDS_RACE_1_HORSE_1, f3.get());
			assertEquals(ODDS_RACE_1_HORSE_1, f4.get());

			// assert that the batch command 'BatchCommandGetOddsForHorse' was in fact
			// executed and that it executed only once
			assertEquals(1, HystrixRequestLog.getCurrentRequest().getExecutedCommands().size());
			HystrixCommand<?> command = HystrixRequestLog.getCurrentRequest().getExecutedCommands()
					.toArray(new HystrixCommand<?>[1])[0];
			// assert the command is the one we're expecting
			assertEquals("BatchCommandGetOddsForHorse", command.getCommandKey().name());
			// confirm that it was a COLLAPSED command execution
			assertTrue(command.getExecutionEvents().contains(HystrixEventType.COLLAPSED));
			// and that it was successful
			assertTrue(command.getExecutionEvents().contains(HystrixEventType.SUCCESS));
			
		} finally {
			context.shutdown();
		}
	}
	
	private List<Racecourse> getRaceCourses(){
		Racecourse course1 = new Racecourse(RACE_1, "Aintree");
		return Arrays.asList(course1);
	}
	
	private List<Horse> getHorsesAtAintree(){
		Horse horse1 = new Horse(HORSE_1, "Red Rum");
		Horse horse2 = new Horse(HORSE_2, "Shergar");
		return Arrays.asList(horse1, horse2);
	}
}
