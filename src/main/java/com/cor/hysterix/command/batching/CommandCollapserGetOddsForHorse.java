package com.cor.hysterix.command.batching;

import java.util.Collection;
import java.util.List;

import com.cor.hysterix.service.BettingService;
import com.netflix.hystrix.HystrixCollapser;
import com.netflix.hystrix.HystrixCommand;

public class CommandCollapserGetOddsForHorse extends HystrixCollapser<List<String>, String, GetOddsForHorseRequest> {

	 	private final GetOddsForHorseRequest key;
	    private BettingService service;

	    public CommandCollapserGetOddsForHorse(GetOddsForHorseRequest key) {
	        this.key = key;
	    }

	    @Override
	    public GetOddsForHorseRequest getRequestArgument() {
	        return key;
	    }

	    @Override
	    protected HystrixCommand<List<String>> createCommand(final Collection<CollapsedRequest<String, GetOddsForHorseRequest>> requests) {
	    	
	    	BatchCommandGetOddsForHorse command = new BatchCommandGetOddsForHorse(requests);
	    	command.setService(service);
	    	
	    	return command;
	    }

	    @Override
	    protected void mapResponseToRequests(List<String> batchResponse, Collection<CollapsedRequest<String, GetOddsForHorseRequest>> requests) {
	        int count = 0;
	        for (CollapsedRequest<String, GetOddsForHorseRequest> request : requests) {
	            request.setResponse(batchResponse.get(count++));
	        }
	    }

		public void setService(BettingService service) {
			this.service = service;
		}
}