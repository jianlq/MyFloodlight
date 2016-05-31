package net.floodlightcontroller.sflowcollector;

import net.floodlightcontroller.core.module.IFloodlightService;

public interface ISflowCollectionService extends IFloodlightService {
	
	public static final String enabledPropStr = "net.floodlightcontroller.sflowcollector.enabled";
	
	public abstract void addSflowListener(ISflowListener listener);
	
	public abstract void removeSflowListener(ISflowListener listener);
}
