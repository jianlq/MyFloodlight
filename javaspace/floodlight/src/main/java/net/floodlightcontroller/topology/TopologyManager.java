/**
 *    Copyright 2013, Big Switch Networks, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 **/

package net.floodlightcontroller.topology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.HAListenerTypeMarker;
import net.floodlightcontroller.core.HARole;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IHAListener;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.LogicalOFMessageCategory;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.SingletonTask;
import net.floodlightcontroller.debugcounter.IDebugCounter;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.debugevent.IDebugEventService;
import net.floodlightcontroller.debugevent.IEventCategory;
import net.floodlightcontroller.debugevent.IDebugEventService.EventColumn;
import net.floodlightcontroller.debugevent.IDebugEventService.EventFieldType;
import net.floodlightcontroller.debugevent.IDebugEventService.EventType;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.packet.BSN;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.LLDP;
import net.floodlightcontroller.restserver.IRestApiService;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Link;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.threadpool.IThreadPoolService;
import net.floodlightcontroller.topology.web.TopologyWebRoutable;

import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Topology manager is responsible for maintaining the controller's notion 
 * of the network graph, as well as implementing tools for finding routes
 * through the topology.  */
//  TopologyManager 模块实现了两个服务接口，即 ITopologyService 和 IRoutingService。
//  拓扑服务：主要是获得网络中一些交换机信息，比如交换机所在的集群(cluster, 一组强连接的 of 交换机)。
//  路由服务：主要是进行网络选路，提供一条网络路径(Route)。

// TopologyService使用的一个重要概念是 OpenFlow "island“ 的想法
// 一个 island 被定义为一组同一 floodlight实例下强连接的 OpenFlow 交换机
public class TopologyManager implements IFloodlightModule, ITopologyService, IRoutingService, ILinkDiscoveryListener, IOFMessageListener {

	protected static Logger log = LoggerFactory.getLogger(TopologyManager.class);

	public static final String MODULE_NAME = "topology";

	public static final String CONTEXT_TUNNEL_ENABLED =
			"com.bigswitch.floodlight.topologymanager.tunnelEnabled";

	/*** Role of the controller.  */
	private HARole role;

	// 下面是维护网络拓扑需要的数据   4个map和1个集合
	/** * Set of ports for each switch*/
	// sw --------> ports 集合
	protected Map<DatapathId, Set<OFPort>> switchPorts;

	/** * Set of links organized by node port tuple*/
	//交换机+端口（sw+port） --------> 所有的links 集合
	protected Map<NodePortTuple, Set<Link>> switchPortLinks;

	/*** Set of direct links */
	//交换机+端口（sw+port） --------> 直连的links 集合
	protected Map<NodePortTuple, Set<Link>> directLinks;

	/*** set of links that are broadcast domain links.*/
	// 交换机+端口（sw+port） --------> 广播域的links 集合
	protected Map<NodePortTuple, Set<Link>> portBroadcastDomainLinks;

	/** * set of tunnel links*/
	protected Set<NodePortTuple> tunnelPorts;    // 隧道端口集合

	protected ILinkDiscoveryService linkDiscoveryService;
	protected IThreadPoolService threadPoolService;
	protected IFloodlightProviderService floodlightProviderService;
	protected IOFSwitchService switchService;
	protected IRestApiService restApiService;
	protected IDebugCounterService debugCounterService;

	// Modules that listen to our updates
	protected ArrayList<ITopologyListener> topologyAware; //listener注册表

	// 由LinkDiscoveryManager来填充，由TopologyManager的 newInstancerTask专门维护处理
	protected BlockingQueue<LDUpdate> ldUpdates;   //链路更新队列，维护网络拓扑

	// These must be accessed using getCurrentInstance(), not directly
	// 注意：不要直接用该域，必须通过getCurrentInstance()获得拓扑实例
	protected TopologyInstance currentInstance; 
	protected TopologyInstance currentInstanceWithoutTunnels;  //不包含隧道端口的实例

	// 任务定义  单例任务
	protected SingletonTask newInstanceTask;  // 单例线程
	/*
 UpdateTopologyWorker 线程专门用于检测 ldUpdates 队列，当队列
非空时，则说明底层链路发生了变化，这样一来，该线程就会调用 updateTopology()方法更
新网络拓扑，并且通知拓扑的 listener，也就是这里的 ITopologyListener。
	 */
	private Date lastUpdateTime;
	//以下三个标志位，在updateTopology进行拓扑更新时用到
	/**
	 * Flag that indicates if links (direct/tunnel/multihop links) were updated as part of LDUpdate.
	 */
	// 标识三种链路，直连/隧道/多跳之中是否进行了更新
	protected boolean linksUpdated;
	/**
	 * Flag that indicates if direct or tunnel links were updated as part of LDUpdate.
	 */
	//标识直连/隧道链路是否跟新
	protected boolean dtLinksUpdated;

	/** Flag that indicates if tunnel ports were updated or not */
	// 是否隧道连接进行了跟新
	protected boolean tunnelPortsUpdated;

	protected int TOPOLOGY_COMPUTE_INTERVAL_MS = 500;

	private IHAListener haListener;

	/**
	 *  Debug Counters
	 */
	protected static final String PACKAGE = TopologyManager.class.getPackage().getName();
	protected IDebugCounter ctrIncoming;

	/**
	 * Debug Events
	 */
	protected IDebugEventService debugEventService;

	/*
	 * Topology Event Updater
	 */
	protected IEventCategory<TopologyEvent> eventCategory;

	/**
	 * Topology Information exposed for a Topology related event - used inside
	 * the BigTopologyEvent class
	 */
	protected class TopologyEventInfo {
		private final int numOpenflowClustersWithTunnels;
		private final int numOpenflowClustersWithoutTunnels;
		private final Map<DatapathId, List<NodePortTuple>> externalPortsMap;
		private final int numTunnelPorts;
		public TopologyEventInfo(int numOpenflowClustersWithTunnels,
				int numOpenflowClustersWithoutTunnels,
				Map<DatapathId, List<NodePortTuple>> externalPortsMap,
				int numTunnelPorts) {
			super();
			this.numOpenflowClustersWithTunnels = numOpenflowClustersWithTunnels;
			this.numOpenflowClustersWithoutTunnels = numOpenflowClustersWithoutTunnels;
			this.externalPortsMap = externalPortsMap;
			this.numTunnelPorts = numTunnelPorts;
		}
		@Override
		public String toString() {
			StringBuilder builder = new StringBuilder();
			builder.append("# Openflow Clusters:");
			builder.append(" { With Tunnels: ");
			builder.append(numOpenflowClustersWithTunnels);
			builder.append(" Without Tunnels: ");
			builder.append(numOpenflowClustersWithoutTunnels);
			builder.append(" }");
			builder.append(", # External Clusters: ");
			int numExternalClusters = externalPortsMap.size();
			builder.append(numExternalClusters);
			if (numExternalClusters > 0) {
				builder.append(" { ");
				int count = 0;
				for (DatapathId extCluster : externalPortsMap.keySet()) {
					builder.append("#" + extCluster + ":Ext Ports: ");
					builder.append(externalPortsMap.get(extCluster).size());
					if (++count < numExternalClusters) {
						builder.append(", ");
					} else {
						builder.append(" ");
					}
				}
				builder.append("}");
			}
			builder.append(", # Tunnel Ports: ");
			builder.append(numTunnelPorts);
			return builder.toString();
		}
	}

	/**
	 * Topology Event class to track topology related events
	 */
	protected class TopologyEvent {
		@EventColumn(name = "Reason", description = EventFieldType.STRING)
		private final String reason;
		@EventColumn(name = "Topology Summary")
		private final TopologyEventInfo topologyInfo;
		public TopologyEvent(String reason,
				TopologyEventInfo topologyInfo) {
			super();
			this.reason = reason;
			this.topologyInfo = topologyInfo;
		}
	}

	//  Getter/Setter methods
	/**
	 * Get the time interval for the period topology updates, if any.
	 * The time returned is in milliseconds.
	 * @return
	 */
	public int getTopologyComputeInterval() {
		return TOPOLOGY_COMPUTE_INTERVAL_MS;
	}

	/**
	 * Set the time interval for the period topology updates, if any.
	 * The time is in milliseconds.
	 * @return
	 */
	public void setTopologyComputeInterval(int time_ms) {
		TOPOLOGY_COMPUTE_INTERVAL_MS = time_ms;
	}

	/**
	 * Thread for recomputing topology.  The thread is always running,
	 * however the function applyUpdates() has a blocking call.
	 */
	/*
	 拓扑模块维护了一个阻塞队列 'ldUpdates'，由于拓扑模块是上面的链路发现模块
    LinkDiscoveryManager 的一个 ILinkDiscoveryListener（目前是唯一的一个），当链路发现模块
    发现底层链路发生变化时，就会调用 TopologyManager 模块实现的 ILinkDiscoveryListener 中
    的 linkDiscoveryUpdate方法，将链路更新 LDUpdate放入到 TopologyManager模块的 ldUpdates
    队列中。
     UpdateTopologyWorker 线程专门用于周期性（500ms）检测 ldUpdates 队列，当队列非空时，则说明底层链路发生了变化，
     这样一来，该线程就会调用 updateTopology()方法更新网络拓扑，并且通知拓扑的 listener，也就是这里的 ITopologyListener。
	 */
	protected class UpdateTopologyWorker implements Runnable {
		// Runnable 多线程实现方式之一
		@Override
		public void run() {
			try {
				if (ldUpdates.peek() != null) {   // 阻塞队列，但是peek()方法不会阻塞
					updateTopology();   //跟新拓扑
				}
				handleMiscellaneousPeriodicEvents(); // null 可以在此自定义操作
			}
			catch (Exception e) {
				log.error("Error in topology instance task thread", e);
			} finally {
			// 不论异常是否出现 单例会延时一个周期，再次执行
				if (floodlightProviderService.getRole() != HARole.STANDBY) {
					newInstanceTask.reschedule(TOPOLOGY_COMPUTE_INTERVAL_MS, TimeUnit.MILLISECONDS); // 500ms
				}
			}
		}
	}

	// To be used for adding any periodic events that's required by topology.
	protected void handleMiscellaneousPeriodicEvents() {
		return;
	}

	// 当newInstanceTask检测到链路发现模块将链路更新放入到ldUpdates队列中时，就会调用此方法更新链路
	public boolean updateTopology() {
		boolean newInstanceFlag;
		linksUpdated = false;
		dtLinksUpdated = false;
		tunnelPortsUpdated = false;
		List<LDUpdate> appliedUpdates = applyUpdates(); //处理链路更新（阻塞调用）
		newInstanceFlag = createNewInstance("link-discovery-updates"); //创建新的拓扑实例
		lastUpdateTime = new Date();    //记录拓扑更新时间
		informListeners(appliedUpdates);    // 通知拓扑监听者listener
		return newInstanceFlag;
	}

	// **********************
	// ILinkDiscoveryListener
	// **********************
  // TopologyManager实现了 ILinkDiscoveryListener 的以下两个接口方法
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) {
		if (log.isTraceEnabled()) {
			log.trace("Queuing update: {}", updateList);
		}
		ldUpdates.addAll(updateList);     // 将链路发现模块产生的链路变化加入到拓扑模块的队列中
	}

	// 当链路更新事件产生时， LinkDiscoveryManager 的 updatesThread 线程会调用
	//TopologyManager 的 linkDiscoveryUpdate 方法，将更新加入到 ldUpdates 队列
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) {
		if (log.isTraceEnabled()) {
			log.trace("Queuing update: {}", update);
		}
		ldUpdates.add(update);
	}

	// ****************
	// ITopologyService
	// ****************

	@Override
	public Map<DatapathId, Set<Link>> getAllLinks(){

		Map<DatapathId, Set<Link>> dpidLinks = new HashMap<DatapathId, Set<Link>>();
		TopologyInstance ti = getCurrentInstance(true);
		Set<DatapathId> switches = ti.getSwitches();

		for(DatapathId s: switches) {
			if (this.switchPorts.get(s) == null) continue;
			for (OFPort p: switchPorts.get(s)) {
				NodePortTuple np = new NodePortTuple(s, p);
				if (this.switchPortLinks.get(np) == null) continue;
				for(Link l: this.switchPortLinks.get(np)) {
					if(dpidLinks.containsKey(s)) {
						dpidLinks.get(s).add(l);
					}
					else {
						dpidLinks.put(s,new HashSet<Link>(Arrays.asList(l)));
					}

				}
			}
		}

		return dpidLinks;
	}

	@Override
	public boolean isEdge(DatapathId sw, OFPort p){
		TopologyInstance ti = getCurrentInstance(true);
		return ti.isEdge(sw, p);
	}

	@Override
	public Set<OFPort> getSwitchBroadcastPorts(DatapathId sw){
		TopologyInstance ti = getCurrentInstance(true);
		return ti.swBroadcastPorts(sw);
	}

	@Override
	public Date getLastUpdateTime() {
		return lastUpdateTime;
	}

	@Override
	public void addListener(ITopologyListener listener) {
		topologyAware.add(listener);
	}

	@Override
	public boolean isAttachmentPointPort(DatapathId switchid, OFPort port) {
		return isAttachmentPointPort(switchid, port, true);
	}

	@Override
	public boolean isAttachmentPointPort(DatapathId switchid, OFPort port, boolean tunnelEnabled) {

		// If the switch port is 'tun-bsn' port, it is not
		// an attachment point port, irrespective of whether
		// a link is found through it or not.
		if (linkDiscoveryService.isTunnelPort(switchid, port))
			return false;

		TopologyInstance ti = getCurrentInstance(tunnelEnabled);

		// if the port is not attachment point port according to
		// topology instance, then return false
		if (ti.isAttachmentPointPort(switchid, port) == false)
			return false;

		// Check whether the port is a physical port. We should not learn
		// attachment points on "special" ports.
		if ((port.getShortPortNumber() & 0xff00) == 0xff00 && port.getShortPortNumber() != (short)0xfffe) return false;

		// Make sure that the port is enabled.
		IOFSwitch sw = switchService.getActiveSwitch(switchid);
		if (sw == null) return false;
		return (sw.portEnabled(port));
	}

	@Override
	public DatapathId getOpenflowDomainId(DatapathId switchId) {
		return getOpenflowDomainId(switchId, true);
	}

	@Override
	public DatapathId getOpenflowDomainId(DatapathId switchId, boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getOpenflowDomainId(switchId);
	}

	@Override
	public boolean inSameOpenflowDomain(DatapathId switch1, DatapathId switch2) {
		return inSameOpenflowDomain(switch1, switch2, true);
	}

	@Override
	public boolean inSameOpenflowDomain(DatapathId switch1, DatapathId switch2,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.inSameOpenflowDomain(switch1, switch2);
	}

	@Override
	public boolean isAllowed(DatapathId sw, OFPort portId) {
		return isAllowed(sw, portId, true);
	}

	@Override
	public boolean isAllowed(DatapathId sw, OFPort portId, boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.isAllowed(sw, portId);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public boolean isIncomingBroadcastAllowed(DatapathId sw, OFPort portId) {
		return isIncomingBroadcastAllowed(sw, portId, true);
	}

	@Override
	public boolean isIncomingBroadcastAllowed(DatapathId sw, OFPort portId,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.isIncomingBroadcastAllowedOnSwitchPort(sw, portId);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	/** Get all the ports connected to the switch */
	@Override
	public Set<OFPort> getPortsWithLinks(DatapathId sw) {
		return getPortsWithLinks(sw, true);
	}

	/** Get all the ports connected to the switch */
	@Override
	public Set<OFPort> getPortsWithLinks(DatapathId sw, boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getPortsWithLinks(sw);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	/** Get all the ports on the target switch (targetSw) on which a
	 * broadcast packet must be sent from a host whose attachment point
	 * is on switch port (src, srcPort).
	 */
	@Override
	public Set<OFPort> getBroadcastPorts(DatapathId targetSw,
			DatapathId src, OFPort srcPort) {
		return getBroadcastPorts(targetSw, src, srcPort, true);
	}

	/** Get all the ports on the target switch (targetSw) on which a
	 * broadcast packet must be sent from a host whose attachment point
	 * is on switch port (src, srcPort).
	 */
	@Override
	public Set<OFPort> getBroadcastPorts(DatapathId targetSw,
			DatapathId src, OFPort srcPort,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getBroadcastPorts(targetSw, src, srcPort);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public NodePortTuple getOutgoingSwitchPort(DatapathId src, OFPort srcPort,
			DatapathId dst, OFPort dstPort) {
		// Use this function to redirect traffic if needed.
		return getOutgoingSwitchPort(src, srcPort, dst, dstPort, true);
	}

	@Override
	public NodePortTuple getOutgoingSwitchPort(DatapathId src, OFPort srcPort,
			DatapathId dst, OFPort dstPort,
			boolean tunnelEnabled) {
		// Use this function to redirect traffic if needed.
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getOutgoingSwitchPort(src, srcPort,
				dst, dstPort);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public NodePortTuple getIncomingSwitchPort(DatapathId src, OFPort srcPort,
			DatapathId dst, OFPort dstPort) {
		return getIncomingSwitchPort(src, srcPort, dst, dstPort, true);
	}

	@Override
	public NodePortTuple getIncomingSwitchPort(DatapathId src, OFPort srcPort,
			DatapathId dst, OFPort dstPort,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getIncomingSwitchPort(src, srcPort,
				dst, dstPort);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	/**
	 * Checks if the two switchports belong to the same broadcast domain.
	 */
	@Override
	public boolean isInSameBroadcastDomain(DatapathId s1, OFPort p1, DatapathId s2,
			OFPort p2) {
		return isInSameBroadcastDomain(s1, p1, s2, p2, true);

	}

	@Override
	public boolean isInSameBroadcastDomain(DatapathId s1, OFPort p1,
			DatapathId s2, OFPort p2,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.inSameBroadcastDomain(s1, p1, s2, p2);

	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	/**
	 * Checks if the switchport is a broadcast domain port or not.
	 */
	@Override
	public boolean isBroadcastDomainPort(DatapathId sw, OFPort port) {
		return isBroadcastDomainPort(sw, port, true);
	}

	@Override
	public boolean isBroadcastDomainPort(DatapathId sw, OFPort port,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.isBroadcastDomainPort(new NodePortTuple(sw, port));
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	/**
	 * Checks if the new attachment point port is consistent with the
	 * old attachment point port.
	 */
	@Override
	public boolean isConsistent(DatapathId oldSw, OFPort oldPort,
			DatapathId newSw, OFPort newPort) {
		return isConsistent(oldSw, oldPort,
				newSw, newPort, true);
	}

	@Override
	public boolean isConsistent(DatapathId oldSw, OFPort oldPort,
			DatapathId newSw, OFPort newPort,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.isConsistent(oldSw, oldPort, newSw, newPort);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public NodePortTuple getAllowedOutgoingBroadcastPort(DatapathId src,
			OFPort srcPort,
			DatapathId dst,
			OFPort dstPort) {
		return getAllowedOutgoingBroadcastPort(src, srcPort,
				dst, dstPort, true);
	}

	@Override
	public NodePortTuple getAllowedOutgoingBroadcastPort(DatapathId src,
			OFPort srcPort,
			DatapathId dst,
			OFPort dstPort,
			boolean tunnelEnabled){
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getAllowedOutgoingBroadcastPort(src, srcPort,
				dst, dstPort);
	}
	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public NodePortTuple
	getAllowedIncomingBroadcastPort(DatapathId src, OFPort srcPort) {
		return getAllowedIncomingBroadcastPort(src,srcPort, true);
	}

	@Override
	public NodePortTuple
	getAllowedIncomingBroadcastPort(DatapathId src, OFPort srcPort,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getAllowedIncomingBroadcastPort(src,srcPort);
	}

	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////
	@Override
	public Set<DatapathId> getSwitchesInOpenflowDomain(DatapathId switchDPID) {
		return getSwitchesInOpenflowDomain(switchDPID, true);
	}

	@Override
	public Set<DatapathId> getSwitchesInOpenflowDomain(DatapathId switchDPID,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getSwitchesInOpenflowDomain(switchDPID);
	}
	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////

	@Override
	public Set<NodePortTuple> getBroadcastDomainPorts() {
		return portBroadcastDomainLinks.keySet();
	}

	@Override
	public Set<NodePortTuple> getTunnelPorts() {
		return tunnelPorts;
	}

	@Override
	public Set<NodePortTuple> getBlockedPorts() {
		Set<NodePortTuple> bp;
		Set<NodePortTuple> blockedPorts =
				new HashSet<NodePortTuple>();

		// As we might have two topologies, simply get the union of
		// both of them and send it.
		bp = getCurrentInstance(true).getBlockedPorts();
		if (bp != null)
			blockedPorts.addAll(bp);

		bp = getCurrentInstance(false).getBlockedPorts();
		if (bp != null)
			blockedPorts.addAll(bp);

		return blockedPorts;
	}
	////////////////////////////////////////////////////////////////////////
	////////////////////////////////////////////////////////////////////////

	// ***************
	// IRoutingService   以下实现IRoutingService服务
	// ***************

	@Override
	public Route getRoute(DatapathId src, DatapathId dst, U64 cookie) {
		return getRoute(src, dst, cookie, true);
	}

	@Override
	public Route getRoute(DatapathId src, DatapathId dst, U64 cookie, boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getRoute(src, dst, cookie);
	}

	@Override
	public Route getRoute(DatapathId src, OFPort srcPort, DatapathId dst, OFPort dstPort, U64 cookie) {
		return getRoute(src, srcPort, dst, dstPort, cookie, true);
	}

	@Override
	public Route getRoute(DatapathId src, OFPort srcPort, DatapathId dst, OFPort dstPort, U64 cookie,
			boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.getRoute(null, src, srcPort, dst, dstPort, cookie);
	}

	@Override
	public boolean routeExists(DatapathId src, DatapathId dst) {
		return routeExists(src, dst, true);
	}

	@Override
	public boolean routeExists(DatapathId src, DatapathId dst, boolean tunnelEnabled) {
		TopologyInstance ti = getCurrentInstance(tunnelEnabled);
		return ti.routeExists(src, dst);
	}

	@Override
	public ArrayList<Route> getRoutes(DatapathId srcDpid, DatapathId dstDpid,
			boolean tunnelEnabled) {
		// Floodlight supports single path routing now

		// return single path now
		ArrayList<Route> result=new ArrayList<Route>();
		result.add(getRoute(srcDpid, dstDpid, U64.of(0), tunnelEnabled));
		return result;
	}

	// ******************
	// IOFMessageListener
	// ******************

	@Override
	public String getName() {
		return MODULE_NAME;
	}

	@Override
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return "linkdiscovery".equals(name);
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}

	@Override   //处理of消息
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		switch (msg.getType()) {
		case PACKET_IN:
			ctrIncoming.increment();  // 消息计数
			return this.processPacketInMessage(sw, (OFPacketIn) msg, cntx);
		default:
			break;
		}

		return Command.CONTINUE;
	}

	// ***************
	// IHAListener
	// ***************

	private class HAListenerDelegate implements IHAListener {
		@Override
		public void transitionToActive() {
			role = HARole.ACTIVE;
			log.debug("Re-computing topology due " +
					"to HA change from STANDBY->ACTIVE");
			newInstanceTask.reschedule(TOPOLOGY_COMPUTE_INTERVAL_MS,
					TimeUnit.MILLISECONDS);
		}

		@Override
		public void controllerNodeIPsChanged(
				Map<String, String> curControllerNodeIPs,
				Map<String, String> addedControllerNodeIPs,
				Map<String, String> removedControllerNodeIPs) {
			// no-op
		}

		@Override
		public String getName() {
			return TopologyManager.this.getName();
		}

		@Override
		public boolean isCallbackOrderingPrereq(HAListenerTypeMarker type,
				String name) {
			return "linkdiscovery".equals(name) ||
					"tunnelmanager".equals(name);
		}

		@Override
		public boolean isCallbackOrderingPostreq(HAListenerTypeMarker type,
				String name) {
			// TODO Auto-generated method stub
			return false;
		}

		@Override
		public void transitionToStandby() {
			// TODO Auto-generated method stub

		}
	}

	// *****************
	// IFloodlightModule
	// *****************

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ITopologyService.class);
		l.add(IRoutingService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService>
	getServiceImpls() {
		Map<Class<? extends IFloodlightService>,
		IFloodlightService> m =
		new HashMap<Class<? extends IFloodlightService>,
		IFloodlightService>();
		// We are the class that implements the service
		m.put(ITopologyService.class, this);
		m.put(IRoutingService.class, this);
		return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>>
	getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ILinkDiscoveryService.class);
		l.add(IThreadPoolService.class);
		l.add(IFloodlightProviderService.class);
		l.add(IOFSwitchService.class);
		l.add(IDebugCounterService.class);
		l.add(IDebugEventService.class);
		l.add(IRestApiService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		linkDiscoveryService = context.getServiceImpl(ILinkDiscoveryService.class);
		threadPoolService = context.getServiceImpl(IThreadPoolService.class);
		floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		switchService = context.getServiceImpl(IOFSwitchService.class);
		restApiService = context.getServiceImpl(IRestApiService.class);
		debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		debugEventService = context.getServiceImpl(IDebugEventService.class);

		switchPorts = new HashMap<DatapathId, Set<OFPort>>();
		switchPortLinks = new HashMap<NodePortTuple, Set<Link>>();
		directLinks = new HashMap<NodePortTuple, Set<Link>>();
		portBroadcastDomainLinks = new HashMap<NodePortTuple, Set<Link>>();
		tunnelPorts = new HashSet<NodePortTuple>();
		topologyAware = new ArrayList<ITopologyListener>();
		ldUpdates = new LinkedBlockingQueue<LDUpdate>();
		haListener = new HAListenerDelegate();
		registerTopologyDebugCounters();
		registerTopologyDebugEvents();
	}

	protected void registerTopologyDebugEvents() throws FloodlightModuleException {
		if (debugEventService == null) {
			log.error("debugEventService should not be null. Has IDebugEventService been loaded previously?");
		}
		eventCategory = debugEventService.buildEvent(TopologyEvent.class)
				.setModuleName(PACKAGE)
				.setEventName("topologyevent")
				.setEventDescription("Topology Computation")
				.setEventType(EventType.ALWAYS_LOG)
				.setBufferCapacity(100)
				.register();
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		clearCurrentTopology();
		// Initialize role to floodlight provider role.
		this.role = floodlightProviderService.getRole();

		ScheduledExecutorService ses = threadPoolService.getScheduledExecutor();
		newInstanceTask = new SingletonTask(ses, new UpdateTopologyWorker());

		if (role != HARole.STANDBY) {
			// 	// 当控制器为ACTIVE时，则创建新线程，执行该单例
			newInstanceTask.reschedule(TOPOLOGY_COMPUTE_INTERVAL_MS, TimeUnit.MILLISECONDS);
		}

		linkDiscoveryService.addListener(this);
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		floodlightProviderService.addHAListener(this.haListener);
		addRestletRoutable();
	}

	private void registerTopologyDebugCounters() throws FloodlightModuleException {
		if (debugCounterService == null) {
			log.error("debugCounterService should not be null. Has IDebugEventService been loaded previously?");
		}
		debugCounterService.registerModule(PACKAGE);
		ctrIncoming = debugCounterService.registerCounter(
				PACKAGE, "incoming",
				"All incoming packets seen by this module");
	}

	protected void addRestletRoutable() {
		restApiService.addRestletRoutable(new TopologyWebRoutable());
	}

	// ****************
	// Internal methods
	// ****************
	/**
	 * If the packet-in switch port is disabled for all data traffic, then
	 * the packet will be dropped.  Otherwise, the packet will follow the
	 * normal processing chain.
	 * @param sw
	 * @param pi
	 * @param cntx
	 * @return
	 */
	protected Command dropFilter(DatapathId sw, OFPacketIn pi,
			FloodlightContext cntx) {
		Command result = Command.CONTINUE;
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
        // 由于目前的检查总是返回 true，所以这个过滤形同虚设，目前版本中还没有任何作用
		// If the input port is not allowed for data traffic, drop everything.  BDDP packets will not reach this stage.
		if (isAllowed(sw, inPort) == false) {
			if (log.isTraceEnabled()) {
				log.trace("Ignoring packet because of topology " +
						"restriction on switch={}, port={}", sw.getLong(), inPort.getPortNumber());
				result = Command.STOP;
			}
		}
		return result;
	}

	/**
	 * TODO This method must be moved to a layer below forwarding
	 * so that anyone can use it.
	 * @param packetData
	 * @param sw
	 * @param ports
	 * @param cntx
	 */
	public void doMultiActionPacketOut(byte[] packetData, IOFSwitch sw,
			Set<OFPort> ports,
			FloodlightContext cntx) {

		if (ports == null) return;
		if (packetData == null || packetData.length <= 0) return;

		//OFPacketOut po = (OFPacketOut) floodlightProvider.getOFMessageFactory().getMessage(OFType.PACKET_OUT);
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		List<OFAction> actions = new ArrayList<OFAction>();
		for(OFPort p: ports) {
			//actions.add(new OFActionOutput(p, (short) 0));
			actions.add(sw.getOFFactory().actions().output(p, 0));
		}

		// set actions
		pob.setActions(actions);
		// set action length
		//po.setActionsLength((short) (OFActionOutput.MINIMUM_LENGTH * ports.size()));
		// set buffer-id to BUFFER_ID_NONE
		pob.setBufferId(OFBufferId.NO_BUFFER);
		// set in-port to OFPP_NONE
		pob.setInPort(OFPort.ZERO);

		// set packet data
		pob.setData(packetData);

		// compute and set packet length.
		//short poLength = (short)(OFPacketOut.MINIMUM_LENGTH + po.getActionsLength() + packetData.length);

		//po.setLength(poLength);

		//ctrIncoming.updatePktOutFMCounterStore(sw, po);
		if (log.isTraceEnabled()) {
			log.trace("write broadcast packet on switch-id={} " +
					"interaces={} packet-data={} packet-out={}",
					new Object[] {sw.getId(), ports, packetData, pob.build()});
		}
		sw.write(pob.build(), LogicalOFMessageCategory.MAIN);
	}

	/**
	 * Get the set of ports to eliminate for sending out BDDP.  The method
	 * returns all the ports that are suppressed for link discovery on the
	 * switch.
	 * packets.
	 * @param sid
	 * @return
	 */
	protected Set<OFPort> getPortsToEliminateForBDDP(DatapathId sid) {
		Set<NodePortTuple> suppressedNptList = linkDiscoveryService.getSuppressLLDPsInfo();
		if (suppressedNptList == null) return null;

		Set<OFPort> resultPorts = new HashSet<OFPort>();
		for(NodePortTuple npt: suppressedNptList) {
			if (npt.getNodeId() == sid) {
				resultPorts.add(npt.getPortId());
			}
		}

		return resultPorts;
	}

	/**
	 * The BDDP packets are forwarded out of all the ports out of an openflowdomain.  Get all the switches in the same openflow
	 * domain as the sw (disabling tunnels).  Then get all the external switch ports and send these packets out.
	 获得与本交换机相连的所有交换机，如果没有就是在它自己上操作。然后筛
     选出其中的广播端口（ external 对外的端口），并向这些端口上广播 LLDP 消息
	 * @param sw
	 * @param pi
	 * @param cntx
	 */
	protected void doFloodBDDP(DatapathId pinSwitch, OFPacketIn pi,
			FloodlightContext cntx) {

		TopologyInstance ti = getCurrentInstance(false); //获得拓扑实例
		Set<DatapathId> switches = ti.getSwitchesInOpenflowDomain(pinSwitch);   //获得与交换机相连的所有交换机

		if (switches == null)
		{
			// indicates no links are connected to the switches  如果没有交换机与其相连，则添加自己
			switches = new HashSet<DatapathId>();
			switches.add(pinSwitch);
		}

		//向连接的所有交换机所有端口发送LLDP消息
		for (DatapathId sid : switches) {     // 如果没有相连的交换机，则会向自己除了packet in的端口上洪泛
			IOFSwitch sw = switchService.getSwitch(sid);
			if (sw == null) continue;
			Collection<OFPort> enabledPorts = sw.getEnabledPortNumbers();
			if (enabledPorts == null)
				continue;
			Set<OFPort> ports = new HashSet<OFPort>();
			ports.addAll(enabledPorts);

			// all the ports known to topology // without tunnels. out of these, we need to choose only those that are
			// broadcast port, otherwise, we should eliminate.   已知所有端口（不包含隧道端口）的拓扑，我们需要筛选出广播端口
			Set<OFPort> portsKnownToTopo = ti.getPortsWithLinks(sid);

			if (portsKnownToTopo != null) {
				for (OFPort p : portsKnownToTopo) {
					NodePortTuple npt =
							new NodePortTuple(sid, p);
					if (ti.isBroadcastDomainPort(npt) == false) {
						ports.remove(p);
					}
				}
			}
          // 从LinkDiscoveryManager中获得哪些禁止LLDP包发送的端口，目前还没有实现
			Set<OFPort> portsToEliminate = getPortsToEliminateForBDDP(sid);
			if (portsToEliminate != null) {
				ports.removeAll(portsToEliminate);
			}

			// remove the incoming switch port   如果当前交换机是收到packet in 的交换机 ，则需要移除收到packet in 的端口
			if (pinSwitch == sid) {
				ports.remove((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)));
			}
			
			// we have all the switch ports to which we need to broadcast.  
			//向sw（收到LLDP包的交换机）的所有端口广播LLDP消息，即是发送packet out消息
			doMultiActionPacketOut(pi.getData(), sw, ports, cntx);
		}
	}

	protected Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		// get the packet-in switch.
		Ethernet eth =
				IFloodlightProviderService.bcStore.
				get(cntx,IFloodlightProviderService.CONTEXT_PI_PAYLOAD);

		if (eth.getPayload() instanceof BSN) {  //取出packet in中的BSN数据包，过滤出普通的数据包
			BSN bsn = (BSN) eth.getPayload();
			if (bsn == null) return Command.STOP;
			if (bsn.getPayload() == null) return Command.STOP;

			// It could be a packet other than BSN LLDP, therefore continue with the regular processing.
			// 如果BSN层上面不是LLDP，则返回，让其他模块继续处理
			if (bsn.getPayload() instanceof LLDP == false)
				return Command.CONTINUE;

			doFloodBDDP(sw.getId(), pi, cntx);  //eth + BSN + LLDP
			return Command.STOP;
		} else {
			return dropFilter(sw.getId(), pi, cntx);  //普通数据包处理，检查以下输入端口是否被金庸，然后传递或禁止
		}
	}
	/**
	 * Updates concerning switch disconnect and port down are not processed.
	 * LinkDiscoveryManager is expected to process those messages and send
	 * multiple link removed messages.  However, all the updates from
	 * LinkDiscoveryManager would be propagated to the listeners of topology.
	 */
	// applyUpdates()  取出 LinkDiscoveryManager 模块产生的链路更新，然后维护交换机端口、隧道端口、链路数
     //据，用于下面生成拓扑实例。并将处理的所有链路更新返回，因为还要将这些更新传递给 topo 事件的 listener。
	public List<LDUpdate> applyUpdates() {
		List<LDUpdate> appliedUpdates = new ArrayList<LDUpdate>();
		LDUpdate update = null;
		while (ldUpdates.peek() != null) {
			try {
				update = ldUpdates.take(); //take方法，阻塞操作，取出队列中的链路更新
			} catch (Exception e) {
				log.error("Error reading link discovery update.", e);
			}
			if (log.isDebugEnabled()) {
				log.debug("Applying update: {}", update);
			}

			//根据链路更新进行UpdateOperation分类处理
			switch (update.getOperation()) {   //转到LDUpdate的UpdateOperation查看跟新类型种类
			case LINK_UPDATED:
				addOrUpdateLink(update.getSrc(), update.getSrcPort(),
						update.getDst(), update.getDstPort(),
						update.getLatency(), update.getType());
				break;
			case LINK_REMOVED:
				removeLink(update.getSrc(), update.getSrcPort(),
						update.getDst(), update.getDstPort());
				break;
			case SWITCH_UPDATED:
				addOrUpdateSwitch(update.getSrc()); //空方法
				break;
			case SWITCH_REMOVED:
				removeSwitch(update.getSrc());
				break;
			case TUNNEL_PORT_ADDED: //添加隧道端口
				addTunnelPort(update.getSrc(), update.getSrcPort());
				break;
			case TUNNEL_PORT_REMOVED: //移除隧道端口
				removeTunnelPort(update.getSrc(), update.getSrcPort());
				break;
			case PORT_UP: case PORT_DOWN:
				break;
			}
			// Add to the list of applied updates.
			appliedUpdates.add(update);
		}
		return (Collections.unmodifiableList(appliedUpdates));  // java语法，返回指定列表的不可修改视图
	}

	protected void addOrUpdateSwitch(DatapathId sw) {
		/*TODO react appropriately
		addSwitch(sw);
		for (OFPortDesc p : switchService.getSwitch(sw).getPorts()) {
			addPortToSwitch(sw, p.getPortNo());
		}
		*/
		return;
	}

	public void addTunnelPort(DatapathId sw, OFPort port) {
		NodePortTuple npt = new NodePortTuple(sw, port);
		tunnelPorts.add(npt);
		tunnelPortsUpdated = true;
	}

	public void removeTunnelPort(DatapathId sw, OFPort port) {
		NodePortTuple npt = new NodePortTuple(sw, port);
		tunnelPorts.remove(npt);
		tunnelPortsUpdated = true;
	}

	public boolean createNewInstance() {
		return createNewInstance("internal");
	}

	/**
	 * This function computes a new topology instance.
	 * It ignores links connected to all broadcast domain ports
	 * and tunnel ports. The method returns if a new instance of
	 * topology was created or not.
	 */
	// 利用 linksUpdated更新的网络数据，生成新的拓扑实例,忽略哪些连接在广播端口和隧道端口的链路
	protected boolean createNewInstance(String reason) {
		Set<NodePortTuple> blockedPorts = new HashSet<NodePortTuple>();
         // NodePortTuple  表示  switch DPID   switch port id组成的tuple
		
		if (!linksUpdated) return false;
         
		// 以下逻辑是：从switchPortLinks中取出所有链路，放入openflowLinks
		Map<NodePortTuple, Set<Link>> openflowLinks;
		// Link 表示源交换机，源端口号  目的交换机，目的端口号，以及它们之间延迟这五个变量
		/*   DatapathId src;    OFPort srcPort;   DatapathId dst;   OFPort dstPort;  latency  */
		openflowLinks =
				new HashMap<NodePortTuple, Set<Link>>();
		Set<NodePortTuple> nptList = switchPortLinks.keySet();   
		//switchPortLinks  交换机+端口（sw+port） --------> 所有的links 集合

		if (nptList != null) {
			for(NodePortTuple npt: nptList) {
				Set<Link> linkSet = switchPortLinks.get(npt);
				if (linkSet == null) continue;
				openflowLinks.put(npt, new HashSet<Link>(linkSet));
			}
		}

		// 获取所有的广播端口
		Set<NodePortTuple> broadcastDomainPorts = identifyBroadcastDomainPorts();
		// 从openflowLinks中移除包含 广播端口的 链路
		for (NodePortTuple npt : broadcastDomainPorts) {
			if (switchPortLinks.get(npt) == null) continue;
			for (Link link : switchPortLinks.get(npt)) {
				removeLinkFromStructure(openflowLinks, link);
			}
		}

		// 从openflowLinks中移除所有包含 隧道端口的链路   
		// ？？？目前模块里面没有加入隧道端口，还未启用  ，自定义需要
		for (NodePortTuple npt: tunnelPorts) {
			if (switchPortLinks.get(npt) == null) continue;
			for (Link link : switchPortLinks.get(npt)) {
				removeLinkFromStructure(openflowLinks, link);
			}
		}
		
		//switchPorts contains only ports that are part of links. Calculation of broadcast ports needs set of all ports. 
		// 由switchPorts得到交换机对应的所有端口的集合
		Map<DatapathId, Set<OFPort>> allPorts = new HashMap<DatapathId, Set<OFPort>>();;
		for (DatapathId sw : switchPorts.keySet()){   //  switchPorts   sw --------> ports 集合
			allPorts.put(sw, this.getPorts(sw));
		}

		// 创建拓扑实例
		TopologyInstance nt = new TopologyInstance(switchPorts,
				blockedPorts,  //阻塞端口  （空） 可以自定义
				openflowLinks,  // 所有链路 由switchPortLinks得来，不包含广播和隧道的链路
				broadcastDomainPorts,  // 广播端口
				tunnelPorts,  //隧道端口
				switchPortLinks,  //  交换机+端口（sw+port） --------> 所有的links 集合
				allPorts); // 交换机对应的所有的端口号

		nt.compute();  // 拓扑计算

		// We set the instances with and without tunnels to be identical.If needed, we may compute them differently.
		currentInstance = nt;  
		currentInstanceWithoutTunnels = nt; // 默认创建的实例是不包含隧道端口的拓扑实例

		TopologyEventInfo topologyInfo =
				new TopologyEventInfo(0, nt.getClusters().size(),
						new HashMap<DatapathId, List<NodePortTuple>>(),
						0);
		eventCategory.newEventWithFlush(new TopologyEvent(reason, topologyInfo));

		return true;
	}

	/**
	 *  We expect every switch port to have at most two links.  Both these
	 *  links must be unidirectional links connecting to the same switch port.
	 *  If not, we will mark this as a broadcast domain port.
	 */
	protected Set<NodePortTuple> identifyBroadcastDomainPorts() {

		Set<NodePortTuple> broadcastDomainPorts =
				new HashSet<NodePortTuple>();
		broadcastDomainPorts.addAll(this.portBroadcastDomainLinks.keySet());

		Set<NodePortTuple> additionalNpt =
				new HashSet<NodePortTuple>();

		// Copy switchPortLinks
		Map<NodePortTuple, Set<Link>> spLinks =
				new HashMap<NodePortTuple, Set<Link>>();
		for (NodePortTuple npt : switchPortLinks.keySet()) {
			spLinks.put(npt, new HashSet<Link>(switchPortLinks.get(npt)));
		}

		for (NodePortTuple npt : spLinks.keySet()) {
			Set<Link> links = spLinks.get(npt);
			boolean bdPort = false;
			ArrayList<Link> linkArray = new ArrayList<Link>();
			if (links.size() > 2) {
				bdPort = true;
			} else if (links.size() == 2) {
				for (Link l : links) {
					linkArray.add(l);
				}
				// now, there should be two links in [0] and [1].
				Link l1 = linkArray.get(0);
				Link l2 = linkArray.get(1);

				// check if these two are symmetric.
				if (!l1.getSrc().equals(l2.getDst()) ||
						!l1.getSrcPort().equals(l2.getDstPort()) ||
						!l1.getDst().equals(l2.getSrc()) ||
						!l1.getDstPort().equals(l2.getSrcPort())) {
					bdPort = true;
				}
			}

			if (bdPort && (broadcastDomainPorts.contains(npt) == false)) {
				additionalNpt.add(npt);
			}
		}

		if (additionalNpt.size() > 0) {
			log.warn("The following switch ports have multiple " +
					"links incident on them, so these ports will be treated " +
					" as braodcast domain ports. {}", additionalNpt);

			broadcastDomainPorts.addAll(additionalNpt);
		}
		return broadcastDomainPorts;
	}

    // 通知拓扑更新事件的 listener。
	// 参数是链路更新信息：链路更新包含了 LinkDiscoveryManager 传递上来的全部链路更新
	public void informListeners(List<LDUpdate> linkUpdates) {

		if (role != null && role != HARole.ACTIVE)
			return;

		for(int i=0; i < topologyAware.size(); ++i) {     //topologyAware   listener注册表
			ITopologyListener listener = topologyAware.get(i);
			listener.topologyChanged(linkUpdates); // 右键  qucik type hierarchy
			//  调用 listener 的 topologyChanged 方法，将链路更新信息传递给 listener 处理。其实也就是让设备管理模块来处理，进行设备的更新
		}
	}

	public void addSwitch(DatapathId sid) {
		if (switchPorts.containsKey(sid) == false) {
			switchPorts.put(sid, new HashSet<OFPort>());
		}
	}

	private void addPortToSwitch(DatapathId s, OFPort p) {
		addSwitch(s);
		switchPorts.get(s).add(p);
	}
	
   //当删除交换机时，会将交换机上的所有隧道端口删除，然后找出交换机的所有端口，并获得其上的所有链路，然后删除
	public void removeSwitch(DatapathId sid) {
		// Delete all the links in the switch, switch and all associated data should be deleted.
		// 移除交换机，会移除交换机上的所有链路，以及相关数据
		if (switchPorts.containsKey(sid) == false) return;

		// Check if any tunnel ports need to be removed.  
		// 移除所有隧道端口
		for(NodePortTuple npt: tunnelPorts) {
			if (npt.getNodeId() == sid) {
				removeTunnelPort(npt.getNodeId(), npt.getPortId());
			}
		}

		//获得交换机的所有端口上的所有链路
		Set<Link> linksToRemove = new HashSet<Link>();
		for(OFPort p: switchPorts.get(sid)) {
			NodePortTuple n1 = new NodePortTuple(sid, p);
			linksToRemove.addAll(switchPortLinks.get(n1));
		}

		if (linksToRemove.isEmpty()) return;

		for(Link link: linksToRemove) {
			removeLink(link);
		}
	}

	/**
	 * Add the given link to the data structure.
	 * @param s
	 * @param l
	 */
	private void addLinkToStructure(Map<NodePortTuple, Set<Link>> s, Link l) {
		NodePortTuple n1 = new NodePortTuple(l.getSrc(), l.getSrcPort());
		NodePortTuple n2 = new NodePortTuple(l.getDst(), l.getDstPort());

		if (s.get(n1) == null) {
			s.put(n1, new HashSet<Link>());
		} 
		if (s.get(n2) == null) {
			s.put(n2, new HashSet<Link>());
		}
		/* 
		 * Since we don't include latency in .equals(), we need
		 * to explicitly remove the existing link (if present).
		 * Otherwise, new latency values for existing links will
		 * never be accepted.
		 */
		s.get(n1).remove(l);
		s.get(n2).remove(l);
		s.get(n1).add(l);
		s.get(n2).add(l);
	}

	/**
	 * Delete the given link from the data structure.  Returns true if the
	 * link was deleted.
	 * @param s
	 * @param l
	 * @return
	 */
	private boolean removeLinkFromStructure(Map<NodePortTuple, Set<Link>> s, Link l) {

		boolean result1 = false, result2 = false;
		NodePortTuple n1 = new NodePortTuple(l.getSrc(), l.getSrcPort());
		NodePortTuple n2 = new NodePortTuple(l.getDst(), l.getDstPort());

		if (s.get(n1) != null) {
			result1 = s.get(n1).remove(l);
			if (s.get(n1).isEmpty()) s.remove(n1);
		}
		if (s.get(n2) != null) {
			result2 = s.get(n2).remove(l);
			if (s.get(n2).isEmpty()) s.remove(n2);
		}
		return result1 || result2;
	}

	protected void addOrUpdateTunnelLink(DatapathId srcId, OFPort srcPort, DatapathId dstId,
			OFPort dstPort, U64 latency) {
		// If you need to handle tunnel links, this is a placeholder.
	}

	public void addOrUpdateLink(DatapathId srcId, OFPort srcPort, DatapathId dstId,
			OFPort dstPort, U64 latency, LinkType type) {
		Link link = new Link(srcId, srcPort, dstId, dstPort, latency);

		if (type.equals(LinkType.MULTIHOP_LINK)) {
			addPortToSwitch(srcId, srcPort);
			addPortToSwitch(dstId, dstPort);
			addLinkToStructure(switchPortLinks, link);

			addLinkToStructure(portBroadcastDomainLinks, link);
			dtLinksUpdated = removeLinkFromStructure(directLinks, link);
			linksUpdated = true;
		} else if (type.equals(LinkType.DIRECT_LINK)) {
			addPortToSwitch(srcId, srcPort);
			addPortToSwitch(dstId, dstPort);
			addLinkToStructure(switchPortLinks, link);

			addLinkToStructure(directLinks, link);
			removeLinkFromStructure(portBroadcastDomainLinks, link);
			dtLinksUpdated = true;
			linksUpdated = true;
		} else if (type.equals(LinkType.TUNNEL)) {
			addOrUpdateTunnelLink(srcId, srcPort, dstId, dstPort, latency);
		}
	}

	public void removeLink(Link link)  {
		linksUpdated = true;
		dtLinksUpdated = removeLinkFromStructure(directLinks, link);
		removeLinkFromStructure(portBroadcastDomainLinks, link);
		removeLinkFromStructure(switchPortLinks, link);

		NodePortTuple srcNpt =
				new NodePortTuple(link.getSrc(), link.getSrcPort());
		NodePortTuple dstNpt =
				new NodePortTuple(link.getDst(), link.getDstPort());

		// Remove switch ports if there are no links through those switch ports
		if (switchPortLinks.get(srcNpt) == null) {
			if (switchPorts.get(srcNpt.getNodeId()) != null)
				switchPorts.get(srcNpt.getNodeId()).remove(srcNpt.getPortId());
		}
		if (switchPortLinks.get(dstNpt) == null) {
			if (switchPorts.get(dstNpt.getNodeId()) != null)
				switchPorts.get(dstNpt.getNodeId()).remove(dstNpt.getPortId());
		}

		// Remove the node if no ports are present
		if (switchPorts.get(srcNpt.getNodeId())!=null &&
				switchPorts.get(srcNpt.getNodeId()).isEmpty()) {
			switchPorts.remove(srcNpt.getNodeId());
		}
		if (switchPorts.get(dstNpt.getNodeId())!=null &&
				switchPorts.get(dstNpt.getNodeId()).isEmpty()) {
			switchPorts.remove(dstNpt.getNodeId());
		}
	}

	public void removeLink(DatapathId srcId, OFPort srcPort,
			DatapathId dstId, OFPort dstPort) {
		Link link = new Link(srcId, srcPort, dstId, dstPort, U64.ZERO /* does not matter for remove (not included in .equals() of Link) */);
		removeLink(link);
	}

	public void clear() {
		switchPorts.clear();
		tunnelPorts.clear();
		switchPortLinks.clear();
		portBroadcastDomainLinks.clear();
		directLinks.clear();
	}

	/**
	 * Clears the current topology. Note that this does NOT
	 * send out updates.
	 */
	public void clearCurrentTopology() {
		this.clear();
		linksUpdated = true;
		dtLinksUpdated = true;
		tunnelPortsUpdated = true;
		createNewInstance("startup");
		lastUpdateTime = new Date();
	}

	/**
	 * Getters.  No Setters.
	 */
	public Map<DatapathId, Set<OFPort>> getSwitchPorts() {
		return switchPorts;
	}

	public Map<NodePortTuple, Set<Link>> getSwitchPortLinks() {
		return switchPortLinks;
	}

	public Map<NodePortTuple, Set<Link>> getPortBroadcastDomainLinks() {
		return portBroadcastDomainLinks;
	}

	public TopologyInstance getCurrentInstance(boolean tunnelEnabled) {
		if (tunnelEnabled)
			return currentInstance;
		else return this.currentInstanceWithoutTunnels;
	}

	public TopologyInstance getCurrentInstance() {
		return this.getCurrentInstance(true);
	}

	/**
	 *  Switch methods
	 */
	@Override
	public Set<OFPort> getPorts(DatapathId sw) {
		IOFSwitch iofSwitch = switchService.getSwitch(sw);
		if (iofSwitch == null) return Collections.emptySet();

		Collection<OFPort> ofpList = iofSwitch.getEnabledPortNumbers();
		if (ofpList == null) return Collections.emptySet();

		Set<OFPort> ports = new HashSet<OFPort>(ofpList);
		Set<OFPort> qPorts = linkDiscoveryService.getQuarantinedPorts(sw);
		if (qPorts != null)
			ports.removeAll(qPorts);

		return ports;
	}
}
