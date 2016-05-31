package net.floodlightcontroller.sflowcollector;

import java.util.Map;

public interface ISflowListener {
	
	public abstract void sflowCollected(Map<String, InterfaceStatistics> ifNameIfStatMap);
}
