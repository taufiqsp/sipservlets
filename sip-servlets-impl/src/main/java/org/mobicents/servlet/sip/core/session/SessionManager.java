/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mobicents.servlet.sip.core.session;

import java.text.ParseException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpSession;
import javax.servlet.sip.SipApplicationSessionEvent;
import javax.servlet.sip.SipApplicationSessionListener;
import javax.sip.header.CallIdHeader;
import javax.sip.header.FromHeader;
import javax.sip.header.ToHeader;
import javax.sip.message.Message;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.mobicents.servlet.sip.message.SipFactoryImpl;
import org.mobicents.servlet.sip.startup.SipContext;

/**
 * This class is used as a central place to get a session be it a sip session
 * or an sip application session. 
 * Here are the semantics of the key used for storing each kind of session :
 * <ul>
 * <li>sip session id = (FROM-ADDR,FROM-TAG,TO-ADDR,CALL-ID,APPNAME)</li>
 * <li>sip app session id = (CALL-ID,APPNAME)<li>
 * </ul>
 * It is not possible to create a sip session or an application session directly,
 * this class should be used to get a session whatever its type. If the session 
 * already exsits it will be returned otherwise it will be created. 
 * One should be expected to remove the sessions from this manager through the
 * remove methods when the sessions are no longer used.
 *
 * FIXME the session manager should be refactored and made part of the sipstandardmanager
 */
public class SessionManager {
	private static transient Log logger = LogFactory.getLog(SessionManager.class);
	
	public final static String TAG_PARAMETER_NAME = "tag";
	
	private Map<SipApplicationSessionKey, SipApplicationSessionImpl> sipApplicationSessions = 
		new HashMap<SipApplicationSessionKey, SipApplicationSessionImpl>();
	//FIXME if it's never cleaned up a memory leak will occur
	//Shall we have a thread scanning for invalid sessions and removing them accordingly ?
	//=> after a chat with ranga the better way to go for now is removing on processDialogTerminated
	private Map<SipSessionKey, SipSessionImpl> sipSessions = 
		new HashMap<SipSessionKey, SipSessionImpl>();

	private Object sipSessionLock = new Object();
	
	private Object sipApplicationSessionLock = new Object();
	
	/**
	 * Computes the sip session key from the input parameters. The sip session
	 * key will be of the form (FROM-ADDR,FROM-TAG,TO-ADDR,CALL-ID,APPNAME)
	 * @param applicationName the name of the application that will be the fifth component of the key
	 * @param message the message to get the 4 components of the key from 
	 * @param inverted TODO
	 * @return the computed key 
	 * @throws NullPointerException if application name is null
	 */
	public static SipSessionKey getSipSessionKey(final String applicationName, final Message message, boolean inverted) {		
		if(applicationName == null) {
			throw new NullPointerException("the application name cannot be null for sip session key creation");
		}
		if(inverted) {
			return new SipSessionKey(
					((ToHeader) message.getHeader(ToHeader.NAME)).getAddress().getURI().toString(),
					((ToHeader) message.getHeader(ToHeader.NAME)).getParameter(TAG_PARAMETER_NAME),
					((FromHeader) message.getHeader(FromHeader.NAME)).getAddress().getURI().toString(),
					((CallIdHeader) message.getHeader(CallIdHeader.NAME)).getCallId(),
					applicationName);
		} else {
			return new SipSessionKey(
				((FromHeader) message.getHeader(FromHeader.NAME)).getAddress().getURI().toString(),
				((FromHeader) message.getHeader(FromHeader.NAME)).getParameter(TAG_PARAMETER_NAME),
				((ToHeader) message.getHeader(ToHeader.NAME)).getAddress().getURI().toString(),
				((CallIdHeader) message.getHeader(CallIdHeader.NAME)).getCallId(),
				applicationName);
		}
	}
	
	/**
	 * Computes the sip application session key from the input parameters. 
	 * The sip application session key will be of the form (CALL-ID,APPNAME)
	 * @param applicationName the name of the application that will be the second component of the key
	 * @param callId the callId composing the first component of the key 
	 * @return the computed key 
	 * @throws NullPointerException if one of the two parameters is null
	 */
	public static SipApplicationSessionKey getSipApplicationSessionKey(final String applicationName, final String callId) {
		if(applicationName == null) {
			throw new NullPointerException("the application name cannot be null for sip application session key creation");
		}
		if(callId == null) {
			throw new NullPointerException("the callId cannot be null for sip application session key creation");
		}
		return new SipApplicationSessionKey(
				callId,
				applicationName);		
	}

	
	/**
	 * Removes a sip session from the manager by its key
	 * @param key the identifier for this session
	 * @return the sip session that had just been removed, null otherwise
	 */
	public SipSessionImpl removeSipSession(final SipSessionKey key) {
		if(logger.isDebugEnabled()) {
			logger.debug("Removing a sip session with the key : " + key);
		}
		synchronized (sipSessionLock) {			
			return sipSessions.remove(key);
		}
	}
	
	/**
	 * Removes a sip application session from the manager by its key
	 * @param key the identifier for this session
	 * @return the sip application session that had just been removed, null otherwise
	 */
	public SipApplicationSessionImpl removeSipApplicationSession(final SipApplicationSessionKey key) {
		if(logger.isDebugEnabled()) {
			logger.debug("Removing a sip application session with the key : " + key);
		}
		synchronized (sipApplicationSessionLock) {			
			return sipApplicationSessions.remove(key);
		}
	}
	
	/**
	 * Retrieve a sip application session from its key. If none exists, one can enforce
	 * the creation through the create parameter to true.
	 * @param key the key identifying the sip application session to retrieve 
	 * @param create if set to true, if no session has been found one will be created
	 * @param SipContext to associate the SipApplicationSession with if create is set to true, if false it won't be used
	 * @return the sip application session matching the key
	 */
	public SipApplicationSessionImpl getSipApplicationSession(final SipApplicationSessionKey key, final boolean create, final SipContext sipContext) {
		SipApplicationSessionImpl sipApplicationSessionImpl = null;
		synchronized (sipApplicationSessionLock) {
			sipApplicationSessionImpl = sipApplicationSessions.get(key);
			if(sipApplicationSessionImpl == null && create) {
				sipApplicationSessionImpl = new SipApplicationSessionImpl(key, sipContext);
				if(logger.isDebugEnabled()) {
					logger.debug("Adding a sip application session with the key : " + key);
				}			
				sipApplicationSessions.put(key, sipApplicationSessionImpl);			
			}
		}
		return sipApplicationSessionImpl;
	}	


	/**
	 * Retrieve a sip session from its key. If none exists, one can enforce
	 * the creation through the create parameter to true. the sip factory cannot be null
	 * if create is set to true.
	 * @param key the key identifying the sip session to retrieve 
	 * @param create if set to true, if no session has been found one will be created
	 * @param sipFactoryImpl needed only for sip session creation.
	 * @param sipApplicationSessionImpl to associate the SipSession with if create is set to true, if false it won't be used
	 * @return the sip session matching the key
	 * @throws IllegalArgumentException if create is set to true and sip Factory is null
	 */
	public SipSessionImpl getSipSession(final SipSessionKey key, final boolean create, final SipFactoryImpl sipFactoryImpl, final SipApplicationSessionImpl sipApplicationSessionImpl) {
		if(create && sipFactoryImpl == null) {
			throw new IllegalArgumentException("the sip factory should not be null");
		}
		SipSessionImpl sipSessionImpl = null;
		synchronized (sipSessionLock) {
			sipSessionImpl = sipSessions.get(key);
			if(sipSessionImpl == null && create) {
				sipSessionImpl = new SipSessionImpl(key, sipFactoryImpl, sipApplicationSessionImpl);
				if(logger.isDebugEnabled()) {
					logger.debug("Adding a sip session with the key : " + key);
				}
				sipSessions.put(key, sipSessionImpl);					
			}		
		}
		return sipSessionImpl;
	}
	
	/**
	 * Retrieve all sip sessions currently hold by the session manager
	 * @return an iterator on the sip sessions
	 */
	public Iterator<SipSessionImpl> getAllSipSessions() {
		return sipSessions.values().iterator();
	}

	/**
	 * Retrieve all sip application sessions currently hold by the session manager
	 * @return an iterator on the sip sessions
	 */
	public Iterator<SipApplicationSessionImpl> getAllSipApplicationSessions() {
		return sipApplicationSessions.values().iterator();
	}
	
	/**
	 * Retrieves the sip application session holding the converged http session in parameter
	 * @param convergedHttpSession the converged session to look up
	 * @return the sip application session holding a reference to it or null if none references it
	 */
	public SipApplicationSessionImpl findSipApplicationSession(HttpSession httpSession) {
		for (SipApplicationSessionImpl sipApplicationSessionImpl : sipApplicationSessions.values()) {			
			if(sipApplicationSessionImpl.findHttpSession(httpSession) != null) {
				return sipApplicationSessionImpl;
			}
		}
		return null;
	}

	/**
	 * 
	 */
	public void dumpSipSessions() {
		if(logger.isDebugEnabled()) {
			logger.debug("sip sessions present in the session manager");
		
			for (SipSessionKey sipSessionKey : sipSessions.keySet()) {
				logger.debug(sipSessionKey.toString());
			}
		}
	}

	/**
	 * 
	 */
	public void dumpSipApplicationSessions() {
		if(logger.isDebugEnabled()) {
			logger.debug("sip application sessions present in the session manager");
		
			for (SipApplicationSessionKey sipApplicationSessionKey : sipApplicationSessions.keySet()) {
				logger.debug(sipApplicationSessionKey.toString());
			}
		}
	}

	/**
	 * Parse a sip application key that was previously generated and put as an http request param
	 * through the encodeURL method of SipApplicationSession
	 * @param sipApplicationKey the stringified version of the sip application key
	 * @return the corresponding sip application session key
	 * @throws ParseException if the stringfied key cannot be parse to a valid key
	 */
	public static SipApplicationSessionKey parseSipApplicationSessionKey(
			String sipApplicationKey) throws ParseException {
		
		int indexOfLeftParenthesis = sipApplicationKey.indexOf("(");
		int indexOfComma = sipApplicationKey.indexOf(",");
		int indexOfRightParenthesis = sipApplicationKey.indexOf(")");
		if(indexOfLeftParenthesis == -1) {
			throw new ParseException("The left parenthesis could not be found in the following key " + sipApplicationKey, 0);
		}
		if(indexOfComma == -1) {
			throw new ParseException("The comma could not be found in the following key " + sipApplicationKey, 0);
		}
		if(indexOfRightParenthesis == -1) {
			throw new ParseException("The right parenthesis could not be found in the following key " + sipApplicationKey, 0);
		}
		
		String callId = sipApplicationKey.substring(indexOfLeftParenthesis + 1, indexOfComma);
		String applicationName = sipApplicationKey.substring(indexOfComma + 1, indexOfRightParenthesis);
		
		return getSipApplicationSessionKey(applicationName, callId);			
	}
}
