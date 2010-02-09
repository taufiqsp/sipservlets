/*
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.mobicents.servlet.sip.core.dispatchers;

import gov.nist.javax.sip.header.extensions.JoinHeader;
import gov.nist.javax.sip.header.extensions.ReplacesHeader;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletException;
import javax.servlet.sip.ProxyBranch;
import javax.servlet.sip.SipServletResponse;
import javax.sip.Dialog;
import javax.sip.SipException;
import javax.sip.SipProvider;
import javax.sip.header.CSeqHeader;
import javax.sip.header.Parameters;
import javax.sip.header.RouteHeader;
import javax.sip.header.SubscriptionStateHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Request;
import javax.sip.message.Response;

import org.apache.log4j.Logger;
import org.mobicents.servlet.sip.JainSipUtils;
import org.mobicents.servlet.sip.annotation.ConcurrencyControlMode;
import org.mobicents.servlet.sip.core.ApplicationRoutingHeaderComposer;
import org.mobicents.servlet.sip.core.session.MobicentsSipApplicationSession;
import org.mobicents.servlet.sip.core.session.MobicentsSipSession;
import org.mobicents.servlet.sip.core.session.SessionManagerUtil;
import org.mobicents.servlet.sip.core.session.SipApplicationSessionKey;
import org.mobicents.servlet.sip.core.session.SipManager;
import org.mobicents.servlet.sip.core.session.SipSessionKey;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.message.SipServletMessageImpl;
import org.mobicents.servlet.sip.message.SipServletRequestImpl;
import org.mobicents.servlet.sip.proxy.ProxyBranchImpl;
import org.mobicents.servlet.sip.proxy.ProxyImpl;
import org.mobicents.servlet.sip.startup.SipContext;

/**
 * This class is responsible for routing and dispatching subsequent request to applications according to JSR 289 Section 
 * 15.6 Responses, Subsequent Requests and Application Path 
 * 
 * It uses route header parameters for proxy apps or to tag parameter for UAS/B2BUA apps 
 * that were previously set by the container on 
 * record route headers or to tag to know which app has to be called 
 * 
 * @author <A HREF="mailto:jean.deruelle@gmail.com">Jean Deruelle</A> 
 *
 */
public class SubsequentRequestDispatcher extends RequestDispatcher {

	private static final Logger logger = Logger.getLogger(SubsequentRequestDispatcher.class);
	
	public SubsequentRequestDispatcher() {}
	
//	public SubsequentRequestDispatcher(
//			SipApplicationDispatcher sipApplicationDispatcher) {
//		super(sipApplicationDispatcher);
//	}

	/**
	 * {@inheritDoc}
	 */
	public void dispatchMessage(final SipProvider sipProvider, SipServletMessageImpl sipServletMessage) throws DispatcherException {
		final SipFactoryImpl sipFactoryImpl = sipApplicationDispatcher.getSipFactory();
		final SipServletRequestImpl sipServletRequest = (SipServletRequestImpl) sipServletMessage;
		if(logger.isDebugEnabled()) {
			logger.debug("Routing of Subsequent Request " + sipServletRequest);
		}	
				
		final Request request = (Request) sipServletRequest.getMessage();
		final Dialog dialog = sipServletRequest.getDialog();		
		final RouteHeader poppedRouteHeader = sipServletRequest.getPoppedRouteHeader();
		final String method = request.getMethod();
		
		String applicationName = null; 
		String applicationId = null;		
		if(poppedRouteHeader != null){
			final Parameters poppedAddress = (Parameters)poppedRouteHeader.getAddress().getURI();
			// Extract information from the Route Header		
			final String applicationNameHashed = poppedAddress.getParameter(RR_PARAM_APPLICATION_NAME);
			if(applicationNameHashed != null && applicationNameHashed.length() > 0) {				
				applicationName = sipApplicationDispatcher.getApplicationNameFromHash(applicationNameHashed);
				applicationId = poppedAddress.getParameter(APP_ID);
			}
		} 
		if(applicationId == null) {
			final ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
			final String arText = toHeader.getTag();
			try {
				final String[] tuple = ApplicationRoutingHeaderComposer.getAppNameAndSessionId(sipApplicationDispatcher, arText);
				applicationName = tuple[0];
				applicationId = tuple[1];
			} catch(IllegalArgumentException e) {
				throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, e);
			}
			if(applicationId == null && applicationName == null) {
				javax.sip.address.SipURI sipRequestUri = (javax.sip.address.SipURI)request.getRequestURI();
				
				final String host = sipRequestUri.getHost();
				final int port = sipRequestUri.getPort();
				final String transport = JainSipUtils.findTransport(request);
				final boolean isAnotherDomain = sipApplicationDispatcher.isExternal(host, port, transport);
				//Issue 823 (http://code.google.com/p/mobicents/issues/detail?id=823) : 
				// Container should proxy statelessly subsequent requests not targeted at itself
				if(isAnotherDomain) {					
					// Some UA are misbehaving and don't follow the non record proxy so they sent subsequent requests to the container (due to oubound proxy set probably) instead of directly to the UA
					// so we proxy statelessly those requests
					if(logger.isDebugEnabled()) {
						logger.debug("No application found to handle this request " + request + " with the following popped route header " + poppedRouteHeader + " so forwarding statelessly to the outside since it is not targeted at the container");
					}
					try {
						sipProvider.sendRequest(request);
					} catch (SipException e) {
						throw new DispatcherException("cannot proxy statelessly outside of the container the following request " + request, e);
					}
					return;
				} else {
					if(Request.ACK.equals(method)) {
						//Means that this is an ACK to a container generated error response, so we can drop it
						if(logger.isDebugEnabled()) {
							logger.debug("The popped Route, application Id and name are null for an ACK, so this is an ACK to a container generated error response, so it is dropped");
						}				
						return ;
					} else {
						if(poppedRouteHeader != null) {
							throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "cannot find the application to handle this subsequent request " + request +
								"in this popped routed header " + poppedRouteHeader);
						} else {
							throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "cannot find the application to handle this subsequent request " + request);
						}
					}
				}
			} 
		}		
		
		boolean inverted = false;
		if(dialog != null && !dialog.isServer()) {
			inverted = true;
		}
		
		final SipContext sipContext = sipApplicationDispatcher.findSipApplication(applicationName);
		if(sipContext == null) {
			if(poppedRouteHeader != null) {
				throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "cannot find the application to handle this subsequent request " + request +
					"in this popped routed header " + poppedRouteHeader);
			} else {
				throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "cannot find the application to handle this subsequent request " + request);
			}
		}
		final SipManager sipManager = (SipManager)sipContext.getManager();		
		final SipApplicationSessionKey sipApplicationSessionKey = SessionManagerUtil.getSipApplicationSessionKey(
				applicationName, 
				applicationId);
	
		MobicentsSipSession tmpSipSession = null;
		MobicentsSipApplicationSession sipApplicationSession = sipManager.getSipApplicationSession(sipApplicationSessionKey, false);
		if(sipApplicationSession == null) {
			if(logger.isDebugEnabled()) {
				sipManager.dumpSipApplicationSessions();
			}
			//trying the join or replaces matching sip app sessions
			final SipApplicationSessionKey joinSipApplicationSessionKey = sipContext.getSipSessionsUtil().getCorrespondingSipApplicationSession(sipApplicationSessionKey, JoinHeader.NAME);
			final SipApplicationSessionKey replacesSipApplicationSessionKey = sipContext.getSipSessionsUtil().getCorrespondingSipApplicationSession(sipApplicationSessionKey, ReplacesHeader.NAME);
			if(joinSipApplicationSessionKey != null) {
				sipApplicationSession = sipManager.getSipApplicationSession(joinSipApplicationSessionKey, false);
			} else if(replacesSipApplicationSessionKey != null) {
				sipApplicationSession = sipManager.getSipApplicationSession(replacesSipApplicationSessionKey, false);
			}
			if(sipApplicationSession == null) {
				if(poppedRouteHeader != null) {
					throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "Cannot find the corresponding sip application session to this subsequent request " + request +
							" with the following popped route header " + sipServletRequest.getPoppedRoute() + ", it may already have been invalidated or timed out");
				} else {
					throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "Cannot find the corresponding sip application session to this subsequent request " + request +
							", it may already have been invalidated or timed out");					
				}
			}
		}
		
		SipSessionKey key = SessionManagerUtil.getSipSessionKey(sipApplicationSession.getKey().getId(), applicationName, request, inverted);
		if(logger.isDebugEnabled()) {
			logger.debug("Trying to find the corresponding sip session with key " + key + " to this subsequent request " + request +
					" with the following popped route header " + sipServletRequest.getPoppedRoute());
		}
		tmpSipSession = sipManager.getSipSession(key, false, sipFactoryImpl, sipApplicationSession);
		
		// Added by Vladimir because the inversion detection on proxied requests doesn't work
		if(tmpSipSession == null) {
			if(logger.isDebugEnabled()) {
				logger.debug("Cannot find the corresponding sip session with key " + key + " to this subsequent request " + request +
						" with the following popped route header " + sipServletRequest.getPoppedRoute() + ". Trying inverted.");
			}
			key = SessionManagerUtil.getSipSessionKey(sipApplicationSession.getKey().getId(), applicationName, request, !inverted);
			tmpSipSession = sipManager.getSipSession(key, false, sipFactoryImpl, sipApplicationSession);
		}
		
		if(tmpSipSession == null) {
			sipManager.dumpSipSessions();
			if(poppedRouteHeader != null) {
				throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "Cannot find the corresponding sip session to this subsequent request " + request +
						" with the following popped route header " + sipServletRequest.getPoppedRoute() + ", it may already have been invalidated or timed out");
			} else {
				throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "Cannot find the corresponding sip session to this subsequent request " + request +
						", it may already have been invalidated or timed out");					
			}			
		} else {
			if(logger.isDebugEnabled()) {
				logger.debug("Inverted try worked. sip session found : " + tmpSipSession.getId());
			}
		}			
		
		final MobicentsSipSession sipSession = tmpSipSession;
		sipServletRequest.setSipSessionKey(key);
		
		// BEGIN validation delegated to the applicationas per JSIP patch for http://code.google.com/p/mobicents/issues/detail?id=766
		if(sipSession.getProxy() == null) {
			boolean isValid = sipSession.validateCSeq(sipServletRequest);
			if(!isValid) {
				return;
			}
		}
		// END of validation for http://code.google.com/p/mobicents/issues/detail?id=766
		
		
		final SubsequentDispatchTask dispatchTask = new SubsequentDispatchTask(sipServletRequest, sipProvider);
		// we enter the sip app here, thus acuiring the semaphore on the session (if concurrency control is set) before the jain sip tx semaphore is released and ensuring that
		// the tx serialization is preserved		
		sipContext.enterSipApp(sipServletRequest, null);
		// if the flag is set we bypass the executor. This flag should be made deprecated 
		if(sipApplicationDispatcher.isBypassRequestExecutor() || ConcurrencyControlMode.Transaction.equals((sipContext.getConcurrencyControlMode()))) {
			dispatchTask.dispatchAndHandleExceptions();
		} else {
			getConcurrencyModelExecutorService(sipContext, sipServletMessage).execute(dispatchTask);
		}
	}	

	public static class SubsequentDispatchTask extends DispatchTask {
		SubsequentDispatchTask(SipServletRequestImpl sipServletRequest, SipProvider sipProvider) {
			super(sipServletRequest, sipProvider);
		}
		
		public void dispatch() throws DispatcherException {
			final SipServletRequestImpl sipServletRequest = (SipServletRequestImpl)sipServletMessage;
			final MobicentsSipSession sipSession = sipServletRequest.getSipSession();
			final MobicentsSipApplicationSession appSession = sipSession.getSipApplicationSession();
			final SipContext sipContext = appSession.getSipContext();
			final Request request = (Request) sipServletRequest.getMessage();
			
			sipContext.enterSipAppHa(sipServletRequest, null, true, true);
			
			final String requestMethod = sipServletRequest.getMethod();
			try {
				sipSession.addOngoingTransaction(sipServletRequest.getTransaction());
				// JSR 289 Section 6.2.1 :
				// any state transition caused by the reception of a SIP message, 
				// the state change must be accomplished by the container before calling 
				// the service() method of any SipServlet to handle the incoming message.
				sipSession.updateStateOnSubsequentRequest(sipServletRequest, true);
				try {
					// RFC 3265 : If a matching NOTIFY request contains a "Subscription-State" of "active" or "pending", it creates
					// a new subscription and a new dialog (unless they have already been
					// created by a matching response, as described above).								
					if(Request.NOTIFY.equals(requestMethod)) {
						final SubscriptionStateHeader subscriptionStateHeader = (SubscriptionStateHeader) 
							sipServletRequest.getMessage().getHeader(SubscriptionStateHeader.NAME);
					 						
						if (subscriptionStateHeader != null && 
											(SubscriptionStateHeader.ACTIVE.equalsIgnoreCase(subscriptionStateHeader.getState()) ||
											SubscriptionStateHeader.PENDING.equalsIgnoreCase(subscriptionStateHeader.getState()))) {					
							sipSession.addSubscription(sipServletRequest);
						}
					}						
							
					// See if the subsequent request should go directly to the proxy
					final ProxyImpl proxy = sipSession.getProxy();
					if(proxy != null) {
						final ProxyBranchImpl finalBranch = proxy.getFinalBranchForSubsequentRequests();
						boolean isPrack = requestMethod.equalsIgnoreCase(Request.PRACK);
						if(finalBranch != null) {								
							proxy.setAckReceived(requestMethod.equalsIgnoreCase(Request.ACK));
							proxy.setOriginalRequest(sipServletRequest);
							// if(!isAckRetranmission) { // We should pass the ack retrans (implied by 10.2.4.1 Handling 2xx Responses to INVITE)
								callServlet(sipServletRequest);

							finalBranch.proxySubsequentRequest(sipServletRequest);
						} else if(isPrack) {
							callServlet(sipServletRequest);
							final List<ProxyBranch> branches = proxy.getProxyBranches();
							for(ProxyBranch pb : branches) {
								final ProxyBranchImpl proxyBranch = (ProxyBranchImpl) pb;
								if(proxyBranch.isWaitingForPrack()) {
									proxyBranch.proxyDialogStateless(sipServletRequest);
									proxyBranch.setWaitingForPrack(false);
								}
							}
						} else {
							logger.warn("Final branch is null, enable debug for more information.");
							if(logger.isDebugEnabled()) {
								logger.debug("Final branch is null, this will probably result in a lost call or request. Here is the request:\n" + request, new
										RuntimeException("Final branch is null"));
							}
						}
					}
					// If it's not for a proxy then it's just an AR, so go to the next application
					else {							
						callServlet(sipServletRequest);				
					}						
				} catch (ServletException e) {
					throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "An unexpected servlet exception occured while processing the following subsequent request " + request, e);
				} catch (SipException e) {
					throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "An unexpected servlet exception occured while processing the following subsequent request " + request, e);
				} catch (IOException e) {				
					throw new DispatcherException(Response.SERVER_INTERNAL_ERROR, "An unexpected servlet exception occured while processing the following subsequent request " + request, e);
				} 	 
			} finally {
				// A subscription is destroyed when a notifier sends a NOTIFY request
				// with a "Subscription-State" of "terminated".			
				if(Request.NOTIFY.equals(requestMethod)) {
					final SubscriptionStateHeader subscriptionStateHeader = (SubscriptionStateHeader) 
						sipServletRequest.getMessage().getHeader(SubscriptionStateHeader.NAME);
				
					if (subscriptionStateHeader != null && 
										SubscriptionStateHeader.TERMINATED.equalsIgnoreCase(subscriptionStateHeader.getState())) {
						sipSession.removeSubscription(sipServletRequest);
					}
				}
				sipContext.exitSipAppHa(sipServletRequest, null);
				sipContext.exitSipApp(sipServletRequest, null);				
			}
			//nothing more needs to be done, either the app acted as UA, PROXY or B2BUA. in any case we stop routing	
		}
	}
	
}