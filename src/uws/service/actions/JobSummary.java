package uws.service.actions;

/*
 * This file is part of UWSLibrary.
 * 
 * UWSLibrary is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * UWSLibrary is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public License
 * along with UWSLibrary.  If not, see <http://www.gnu.org/licenses/>.
 * 
 * Copyright 2012,2014 - UDS/Centre de Données astronomiques de Strasbourg (CDS),
 *                       Astronomisches Rechen Institut (ARI)
 */

import java.io.IOException;

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import uws.UWSException;
import uws.job.ExecutionPhase;
import uws.job.JobObserver;
import uws.job.UWSJob;
import uws.job.serializer.UWSSerializer;
import uws.job.user.JobOwner;
import uws.service.UWSService;
import uws.service.UWSUrl;
import uws.service.log.UWSLog.LogLevel;

/**
 * <p>The "Get Job" action of a UWS.</p>
 * 
 * <p><i><u>Note:</u> The corresponding name is {@link UWSAction#JOB_SUMMARY}.</i></p>
 * 
 * <p>This action returns the summary of the job specified in the given UWS URL.
 * This summary is serialized by the {@link UWSSerializer} choosed in function of the HTTP Accept header.</p>
 * 
 * @author Gr&eacute;gory Mantelet (CDS;ARI)
 * @version 4.1 (08/2014)
 */
public class JobSummary extends UWSAction {
	private static final long serialVersionUID = 1L;

	public JobSummary(UWSService u){
		super(u);
	}

	/**
	 * @see UWSAction#JOB_SUMMARY
	 * @see uws.service.actions.UWSAction#getName()
	 */
	@Override
	public String getName(){
		return JOB_SUMMARY;
	}

	@Override
	public String getDescription(){
		return "Lets getting a summary of the specified job. (URL: {baseUWS_URL}/{jobListName}/{job-id}, Method: HTTP-GET, No parameter)";
	}

	/**
	 * Checks whether:
	 * <ul>
	 * 	<li>a job list name is specified in the given UWS URL <i>(<u>note:</u> the existence of the jobs list is not checked)</i>,</li>
	 * 	<li>a job ID is given in the UWS URL <i>(<u>note:</u> the existence of the job is not checked)</i>,</li>
	 * 	<li>there is no job attribute,</li>
	 * 	<li>the HTTP method is HTTP-GET.</li>
	 * </ul>
	 * 
	 * @see uws.service.actions.UWSAction#match(uws.service.UWSUrl, java.lang.String, javax.servlet.http.HttpServletRequest)
	 */
	@Override
	public boolean match(UWSUrl urlInterpreter, JobOwner user, HttpServletRequest request) throws UWSException{
		return (urlInterpreter.hasJobList() && urlInterpreter.hasJob() && !urlInterpreter.hasAttribute() && request.getMethod().equalsIgnoreCase("get"));
	}

	/**
	 * Gets the specified job <i>(and throw an error if not found)</i>,
	 * chooses the serializer and write the serialization of the job in the given response.
	 * 
	 * @see #getJob(UWSUrl, String)
	 * @see UWSService#getSerializer(String)
	 * @see UWSJob#serialize(ServletOutputStream, UWSSerializer)
	 * 
	 * @see uws.service.actions.UWSAction#apply(uws.service.UWSUrl, java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
	 */
	@Override
	public boolean apply(UWSUrl urlInterpreter, JobOwner user, HttpServletRequest request, HttpServletResponse response) throws UWSException, IOException{
		// Get the job:
		UWSJob job = getJob(urlInterpreter);
		
		/* TODO Finish the blocking behaviour!
		 *      The blocking should stop when the request is aborted. */
		
		if (request.getParameter("WAIT") != null && (job.getPhase() == ExecutionPhase.PENDING || job.getPhase() == ExecutionPhase.QUEUED || job.getPhase() == ExecutionPhase.EXECUTING)){
			synchronized(Thread.currentThread()){
				WaitObserver observer = new WaitObserver(Thread.currentThread());
				job.addObserver(observer);
				long waitingTime = 0;
				try{
					waitingTime = Long.parseLong(request.getParameter("WAIT"));
				}catch(NumberFormatException nfe){}
				try{
					if (waitingTime > 0)
						Thread.currentThread().wait(waitingTime*1000);
					else
						Thread.currentThread().wait();
				}catch(Throwable t){
					t.printStackTrace();
				}finally{
					job.removeObserver(observer);
				}
			}
		}

		// Write the job summary:
		UWSSerializer serializer = uws.getSerializer(request.getHeader("Accept"));
		response.setContentType(serializer.getMimeType());
		try{
			job.serialize(response.getOutputStream(), serializer, user);
		}catch(Exception e){
			if (!(e instanceof UWSException)){
				getLogger().logUWS(LogLevel.ERROR, urlInterpreter, "SERIALIZE", "Can not serialize the job \"" + job.getJobId() + "\"!", e);
				throw new UWSException(UWSException.INTERNAL_SERVER_ERROR, e, "Can not format properly the job \"" + job.getJobId() + "\"!");
			}else
				throw (UWSException)e;
		}

		return true;
	}
	
	public static class WaitObserver implements JobObserver {

		private final Thread waitingThread;
		
		public WaitObserver(final Thread thread){
			waitingThread = thread;
		}
		
		@Override
		public void update(final UWSJob job, final ExecutionPhase oldPhase, final ExecutionPhase newPhase) throws UWSException {
			if (oldPhase != null && newPhase != null && oldPhase != newPhase){
				synchronized(waitingThread){
					job.removeObserver(this);
					waitingThread.notify();
					System.out.println("[DEBUG] OBSERVER UPDATE ("+oldPhase+" -> "+newPhase+")!"); // TODO DEBUG
				}
			}
		}
		
	}

}
