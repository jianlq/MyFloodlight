package net.floodlightcontroller.sflowcollector;

import net.floodlightcontroller.core.module.IFloodlightService;
import java.util.Set;

public interface IIfIndexCollectionService extends IFloodlightService {
	/**
	 * Get the Name of an interface.
	 * @param snmpAgentIp the ip address of the snmp agent
	 * @param ifIndex the index of the interface
	 * @return the Name
	 */
	public abstract String getIfNameByIfIndex(String snmpAgentIp, Integer ifIndex);
	public abstract Set<String> returnIfnames(String snmpAgentIp);
}
