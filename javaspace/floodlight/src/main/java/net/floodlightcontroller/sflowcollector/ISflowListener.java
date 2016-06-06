package net.floodlightcontroller.sflowcollector;

import java.util.Map;

public interface ISflowListener {
	
	public abstract void sflowCollectedBase(Map<String, InterfaceStatistics> ifNameIfStatMap);
	public abstract void sflowCollected(Map<Integer, InterfaceStatistics> ifNameIfStatMap);
}
