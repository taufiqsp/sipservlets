package org.mobicents.slee.container.event;

import java.util.LinkedList;
import java.util.Set;

import javax.slee.ActivityContextInterface;
import javax.slee.Address;
import javax.slee.EventTypeID;
import javax.slee.SLEEException;
import javax.slee.ServiceID;
import javax.slee.TransactionRequiredLocalException;
import javax.slee.resource.FailureReason;

import org.mobicents.slee.container.activity.ActivityContextHandle;
import org.mobicents.slee.container.activity.LocalActivityContext;
import org.mobicents.slee.container.component.service.ServiceComponent;

/**
 * A differed event. When an SBB posts an event, it winds up as one of these.
 * When the tx commits, it actually makes it into the event queue.
 * 
 * @author M. Ranganathan
 * @author Ivelin Ivanov (refactoring)
 * @author eduardomartins
 * 
 */
public class EventContextImpl extends LazyStoredEventContext implements EventContext {

	private final EventContextData data;
	private EventContextSuspensionHandler suspensionHandler;

	public EventContextImpl(EventContextData data, EventContextFactoryImpl factory) {
		super(factory);
		this.data = data;
	}

	private void suspensionHandlerLazyInit() {
		if (suspensionHandler == null) {
			suspensionHandler = new EventContextSuspensionHandler(this);
		}
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#barrierEvent(org.mobicents.slee.container.event.EventContext)
	 */
	public void barrierEvent(EventContext event) {
		suspensionHandlerLazyInit();
		suspensionHandler.barrierEvent(event);
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#getActiveServicesToProcessEventAsInitial()
	 */
	public LinkedList<ServiceComponent> getActiveServicesToProcessEventAsInitial() {
		return data.getActiveServicesToProcessEventAsInitial();
	}
	
	/*
	 * (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#getActivityContextHandle()
	 */
	public ActivityContextHandle getActivityContextHandle() {
		return data.getLocalActivityContext().getActivityContextHandle();
	}

	/* (non-Javadoc)
	 * @see javax.slee.EventContext#getActivityContextInterface()
	 */
	public ActivityContextInterface getActivityContextInterface() {
		return factory.getSleeContainer().getActivityContextFactory().getActivityContext(getActivityContextHandle()).getActivityContextInterface();
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#getReferencesHandler()
	 */
	public ReferencesHandler getReferencesHandler() {
		return data.getReferencesHandler();
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#getSbbEntitiesThatHandledEvent()
	 */
	public Set<String> getSbbEntitiesThatHandledEvent() {
		return data.getSbbEntitiesThatHandledEvent();
	}
	
	/* (non-Javadoc)
	 * @see javax.slee.EventContext#isSuspended()
	 */
	public boolean isSuspended() throws TransactionRequiredLocalException,
			SLEEException {
		suspensionHandlerLazyInit();
		return suspensionHandler.isSuspended();
	}
	 /* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#isSuspendedNotTransacted()
	 */
	public boolean isSuspendedNotTransacted() {
		suspensionHandlerLazyInit();
		return suspensionHandler.isSuspendedNotTransacted();
	}
	
	/* (non-Javadoc)
	 * @see javax.slee.EventContext#resumeDelivery()
	 */
	public void resumeDelivery() throws IllegalStateException,
			TransactionRequiredLocalException, SLEEException {
		suspensionHandlerLazyInit();
		suspensionHandler.resumeDelivery();
	}
	
	/* (non-Javadoc)
	 * @see javax.slee.EventContext#suspendDelivery()
	 */
	public void suspendDelivery() throws IllegalStateException,
			TransactionRequiredLocalException, SLEEException {
		suspensionHandlerLazyInit();
		suspensionHandler.suspendDelivery();
	}
	
	/* (non-Javadoc)
	 * @see javax.slee.EventContext#suspendDelivery(int)
	 */
	public void suspendDelivery(int arg0) throws IllegalArgumentException,
			IllegalStateException, TransactionRequiredLocalException,
			SLEEException {
		suspensionHandlerLazyInit();
		suspensionHandler.suspendDelivery(arg0);
	}
	
	public Address getAddress() {
		return data.getAddress();
	}

	public Object getEvent() {
		return data.getEventObject();
	}

	public EventTypeID getEventTypeId() {
		return data.getEventTypeId();
	}

	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#eventProcessingFailed(javax.slee.resource.FailureReason)
	 */
	public void eventProcessingFailed(FailureReason reason) {
		if (data.getFailedCallback() != null) {
			try {
				data.getFailedCallback().eventProcessingFailed(reason);
			}
			catch (Throwable e) {
				// ignore
			}
		}
		eventUnreferenced();
	}

	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#eventProcessingSucceed()
	 */
	public void eventProcessingSucceed() {
		if (data.getSucceedCallback() != null) {
			data.getSucceedCallback().eventProcessingSucceed();
			// ensure failed never gets called, even if event is refired
			data.unsetFailedCallback();
		}
	}
	
	public LocalActivityContext getLocalActivityContext() {
		return data.getLocalActivityContext();
	}

	public ServiceID getService() {
		return data.getService();
	}

	public EventProcessingSucceedCallback getSucceedCallback() {
		return data.getSucceedCallback();
	}

	public EventUnreferencedCallback getUnreferencedCallback() {
		return data.getUnreferencedCallback();
	}

	@Override
	public String toString() {
		return new StringBuilder("EventContext[").append(
				data).append(']').toString();
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.core.event.SleeEvent#isActivityEndEvent()
	 */
	public boolean isActivityEndEvent() {
		return false;
	}

	/**
	 * 
	 */
	protected void eventUnreferenced() {
		super.remove();
		if (data.getUnreferencedCallback() != null) {
			data.getUnreferencedCallback().eventUnreferenced();
		}		
	}
	
	/* (non-Javadoc)
	 * @see org.mobicents.slee.container.event.EventContext#unreferencedCallbackRequiresTransaction()
	 */
	public boolean unreferencedCallbackRequiresTransaction() {
		final EventUnreferencedCallback unreferencedCallback = data.getUnreferencedCallback();
		if (unreferencedCallback == null) {
			return false;
		}
		else {
			return unreferencedCallback.requiresTransaction();
		}
	}
	
}