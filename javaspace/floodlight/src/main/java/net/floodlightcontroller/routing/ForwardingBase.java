/**
 *    Copyright 2011, Big Switch Networks, Inc.
 *    Originally created by David Erickson, Stanford University
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

package net.floodlightcontroller.routing;

import java.io.IOException;
import java.util.EnumSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFMessageListener;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.packet.ARP;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPacket;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.statistics.IStatisticsService;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.MatchUtils;
import net.floodlightcontroller.util.OFDPAUtils;
import net.floodlightcontroller.util.OFMessageDamper;
import net.floodlightcontroller.util.TimedCache;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFFlowModFlags;
import org.projectfloodlight.openflow.protocol.OFMessage;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.action.OFActionOutput;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.TransportPort;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base class for implementing a forwarding module.  Forwarding is
 * responsible for programming flows to a switch in response to a policy
 * decision.
 */
/*
  ForwardBase路由模块，首先在上下文context查找之前模块是否已有路由策略，
  如无则检查是否数据包是广播的，如是则Flood。否则调用doForwardFlow，
  我们主要看这个方法，具体的，从上下文中获取源目主机信息，
  如果不在同一个island上，则Flood；如果在同一个端口，那就是同一台主机，则放弃；
  否则调用routingEngine.getRoute获得路由。这个才是最重要的，这里使用了TopologyInstance的getRoute方法，
  计算出路由路径，然后调用pushRoute()将路由所对应的路径推送给交换机，
  如果是多跳路径，则将路由策略推送给该路径上的所有交换机（ForwardBase.pushRoute()）。
 */
 
public abstract class ForwardingBase implements IOFMessageListener {
	protected static Logger log = LoggerFactory.getLogger(ForwardingBase.class);

	protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // TODO: find sweet spot
	protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms

	public static int FLOWMOD_DEFAULT_IDLE_TIMEOUT = 5; // in seconds
	public static int FLOWMOD_DEFAULT_HARD_TIMEOUT = 0; // infinite
	public static int FLOWMOD_DEFAULT_PRIORITY = 1; // 0 is the default table-miss flow in OF1.3+, so we need to use 1
	
	protected static boolean FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = false;
	
	protected static boolean FLOWMOD_DEFAULT_MATCH_VLAN = true;
	protected static boolean FLOWMOD_DEFAULT_MATCH_MAC = true;
	protected static boolean FLOWMOD_DEFAULT_MATCH_IP_ADDR = true;
	protected static boolean FLOWMOD_DEFAULT_MATCH_TRANSPORT = true;

	protected static final short FLOWMOD_DEFAULT_IDLE_TIMEOUT_CONSTANT = 5;
	protected static final short FLOWMOD_DEFAULT_HARD_TIMEOUT_CONSTANT = 0;
	
	protected static boolean FLOOD_ALL_ARP_PACKETS = false;

	protected IFloodlightProviderService floodlightProviderService;
	protected IOFSwitchService switchService;
	protected IDeviceService deviceManagerService;
	protected IRoutingService routingEngineService;
	protected ITopologyService topologyService;
	protected IDebugCounterService debugCounterService;
	protected IStatisticsService statisticsService; // jian 2016 -5 -20 

	protected OFMessageDamper messageDamper; //of 消息阻尼器

	// for broadcast loop suppression
	protected boolean broadcastCacheFeature = true;
	public final int prime1 = 2633;  // for hash calculation
	public final static int prime2 = 4357;  // for hash calculation
	public TimedCache<Long> broadcastCache = new TimedCache<Long>(100, 5*1000);  // 5 seconds interval;

	// 目前注册了app的有ForwardingBase，OFSwitch，StaticFLowEntryPusher，VirtualNetworkFilter四个模块
	// 若用户想要自定义模块向交换机下发流表项，则必须注册成为 App，生成自己的 cookie，否则会导致下发失败
	// flow-mod - for use in the cookie
	// app id 用于注册app和生成cookie
	public static final int FORWARDING_APP_ID = 2; // TODO: This must be managed
	// by a global APP_ID class
	static {      //注册app
		AppCookie.registerApp(FORWARDING_APP_ID, "Forwarding");
	}     // 生成cookie
	public static final U64 appCookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);

	// Comparator for sorting by SwitchCluster
	public Comparator<SwitchPort> clusterIdComparator =
			new Comparator<SwitchPort>() {
		@Override
		public int compare(SwitchPort d1, SwitchPort d2) {
			DatapathId d1ClusterId = topologyService.getOpenflowDomainId(d1.getSwitchDPID());
			DatapathId d2ClusterId = topologyService.getOpenflowDomainId(d2.getSwitchDPID());
			return d1ClusterId.compareTo(d2ClusterId);
		}
	};

	protected void init() {
		// 初始化 messageDamper 对象（ of 消息阻尼器，防止短时间内发送同样的 of 消息）
		// 三个参数  第一个是capacity ， 表示缓存的of消息容量； 第二个是缓存的of消息的类型集，第三个是重复时间设置
		messageDamper = new OFMessageDamper(OFMESSAGE_DAMPER_CAPACITY,
				EnumSet.of(OFType.FLOW_MOD),
				OFMESSAGE_DAMPER_TIMEOUT);
		// protected static int OFMESSAGE_DAMPER_CAPACITY = 10000; // TODO: find sweet spot
		//protected static int OFMESSAGE_DAMPER_TIMEOUT = 250; // ms
		//由上面两个定义可以看出，of 消息的缓存容量为 10000 条，会避免 250ms 内发送重复消息
	}

	protected void startUp() {
		floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
	}

	@Override
	public String getName() {
		return "forwarding";
	}

	/**
	 * All subclasses must define this function if they want any specific  forwarding action
	 *
	 * @param sw
	 *            Switch that the packet came in from
	 * @param pi
	 *            The packet that came in
	 * @param decision
	 *            Any decision made by a policy engine
	 */
	public abstract Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, 
			IRoutingDecision decision, FloodlightContext cntx);

	@Override  //IOFMessageListener's receive() function.
	// This is the method Floodlight uses to call listeners with OpenFlow messages
	public Command receive(IOFSwitch sw, OFMessage msg, FloodlightContext cntx) {
		 Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		switch (msg.getType()) {
		case PACKET_IN:
			// 检查路由决策
			IRoutingDecision decision = null;
			if (cntx != null) {
				decision = RoutingDecision.rtStore.get(cntx, IRoutingDecision.CONTEXT_DECISION);
			}
			
			// packet in处理  2016 - 5 -30
			MacAddress srcMac = eth.getSourceMACAddress();
			VlanVid vlanID = VlanVid.ofVlan(eth.getVlanID());
			if(eth.getEtherType()==EthType.IPv4){
				IPv4 ipv4 = (IPv4) eth.getPayload();
				byte[] ipOptions = ipv4.getOptions();
				IPv4Address dstIp = ipv4.getDestinationAddress();
				
				if(ipv4.getProtocol().equals(IpProtocol.TCP)){
					TCP tcp = (TCP) ipv4.getPayload();
					TransportPort srcPort = tcp.getSourcePort();
					TransportPort dstPort = tcp.getDestinationPort();
				}
			}else if(eth.getEtherType() == EthType.ARP){
				ARP arp = (ARP) eth.getPayload();
				boolean gratuitous = arp.isGratuitous();
			}
			
           // 调用processPacketInMessage()方法处理of消息
		    // 该方法在子类Forwarding中实现
			 /** log.info("forwarding receive ");
			  /* log.info("   forwarding module receive msg  eth Type :{} , from switch: {}",
					   eth.getEtherType().toString(),
	                    sw.getId().toString());
	                    */  //2016 -5 -19
			return this.processPacketInMessage(sw, (OFPacketIn) msg, decision, cntx);
		default:
			break;
		}
		return Command.CONTINUE;
	}

	/**
	 * Push routes from back to front   从后往前push路由
	 * @param route Route to push
	 * @param match OpenFlow fields to match on
	 * @param srcSwPort Source switch port for the first hop
	 * @param dstSwPort Destination switch port for final hop
	 * @param cookie The cookie to set in each flow_mod
	 * @param cntx The floodlight context
	 * @param requestFlowRemovedNotification if set to true then the switch would
	 *        send a flow mod removal notification when the flow mod expires  当流超时时是否需要给控制器发送移除流通知
	 * @param flowModCommand flow mod. command to use, e.g. OFFlowMod.OFPFC_ADD,
	 *        OFFlowMod.OFPFC_MODIFY etc.
	 * @return true if a packet out was sent on the first-hop switch of this route
	 */
	public boolean pushRoute(Route route, Match match, OFPacketIn pi,
			DatapathId pinSwitch, U64 cookie, FloodlightContext cntx,
			boolean requestFlowRemovedNotification, OFFlowModCommand flowModCommand) {
		
		boolean packetOutSent = false;
       // 第一步，从路径中获得所有的交换机端口；
		// 将路径信息放入switchPortList 链表，由节点（交换机）和端口组成的元祖
		List<NodePortTuple> switchPortList = route.getPath();
		
		/**
		 * log.info("Route    size = {}",route.getPath().size() );
		 * // 第二步，从后往前，每次出去两个交换机端口。这两个端口一次为同一交换机的入端口和出端口； //
		 * 注意for循环的步长为2，反向搭建通路，且与下文的设置出入端口有关，每个交换机包含两条元祖，分别是入端口和出端口
		 */
		for (int indx = switchPortList.size() - 1; indx > 0; indx -= 2) {
			// indx and indx-1 will always have the same switch DPID.
			DatapathId switchDPID = switchPortList.get(indx).getNodeId();
			IOFSwitch sw = switchService.getSwitch(switchDPID);

			if (sw == null) {
				if (log.isWarnEnabled()) {
					log.warn("Unable to push route, switch at DPID {} " + "not available", switchDPID);
				}
				return packetOutSent;
			}
			
			// need to build flow mod based on what type it is. Cannot set
			// command later
			/*
			 * 第三步，构建 flow add 消息，设置相应的匹配项，该类型数据包在当前交换机上的入端口和 出端口，如果需要交换机回复 flow
			 * removed 消息，就设置 OFFlowModFlags.SEND_FLOW_REM 标志（默认源码未设置，默认为
			 * false；当开发者需要设置该标志时，应添加相应的设置代码）；
			 */
			OFFlowMod.Builder fmb;
			switch (flowModCommand) {
			case ADD:
				fmb = sw.getOFFactory().buildFlowAdd();
				break;
			case DELETE:
				fmb = sw.getOFFactory().buildFlowDelete();
				break;
			case DELETE_STRICT:
				fmb = sw.getOFFactory().buildFlowDeleteStrict();
				break;
			case MODIFY:
				fmb = sw.getOFFactory().buildFlowModify();
				break;
			default:
				log.error("Could not decode OFFlowModCommand. Using MODIFY_STRICT. (Should another be used as the default?)");        
			case MODIFY_STRICT:
				fmb = sw.getOFFactory().buildFlowModifyStrict();
				break;			
			}
			
			OFActionOutput.Builder aob = sw.getOFFactory().actions().buildOutput();
			List<OFAction> actions = new ArrayList<OFAction>();	
 			Match.Builder mb = MatchUtils.convertToVersion(match, sw.getOFFactory().getVersion());
 			
			// set input and output ports on the switch
			OFPort outPort = switchPortList.get(indx).getPortId();
			OFPort inPort = switchPortList.get(indx - 1).getPortId();
			mb.setExact(MatchField.IN_PORT, inPort);
			aob.setPort(outPort);
			aob.setMaxLen(Integer.MAX_VALUE);
			actions.add(aob.build());
			
			if (FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG || requestFlowRemovedNotification) {
				Set<OFFlowModFlags> flags = new HashSet<>();
				flags.add(OFFlowModFlags.SEND_FLOW_REM);
				fmb.setFlags(flags);
			}
			
			fmb.setMatch(mb.build())
			.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
			.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
			.setBufferId(OFBufferId.NO_BUFFER)
			.setCookie(cookie)
			.setOutPort(outPort)
			.setPriority(FLOWMOD_DEFAULT_PRIORITY);
			
			FlowModUtils.setActions(fmb, actions, sw);
			
			try {
				if (log.isTraceEnabled()) {
					log.trace("Pushing Route flowmod routeIndx={} " +
							"sw={} inPort={} outPort={}",
							new Object[] {indx,
							sw,
							fmb.getMatch().get(MatchField.IN_PORT),
							outPort });
				}
				
				if (OFDPAUtils.isOFDPASwitch(sw)) {
					OFDPAUtils.addLearningSwitchFlow(sw, cookie, 
							FLOWMOD_DEFAULT_PRIORITY, 
							FLOWMOD_DEFAULT_HARD_TIMEOUT,
							FLOWMOD_DEFAULT_IDLE_TIMEOUT,
							fmb.getMatch(), 
							null, // TODO how to determine output VLAN for lookup of L2 interface group
							outPort);
				} else {   //第四步，向当前交换机发送该消息。
					messageDamper.write(sw, fmb.build());
					
					/** jian 2016 - 4- 6 
					 log.info("   pushRoute  ,switch = {}, outPort = {}",sw.getId(),outPort); 
					*/  
				}

				/* Push the packet out the first hop switch */
				// 第五步 ：检查当前交换机是否为接收到 packet in 的交换机，如果是就发送 packet out 消息
				if (sw.getId().equals(pinSwitch) &&
						!fmb.getCommand().equals(OFFlowModCommand.DELETE) &&
						!fmb.getCommand().equals(OFFlowModCommand.DELETE_STRICT)) {
					/* Use the buffered packet at the switch, if there's one stored */		
					pushPacket(sw, pi, outPort, true, cntx);     // 调用pushPacket（）发送packet out消息
					packetOutSent = true;
				}
				//第六步，跳转至第二步，取出前面的端口号，直至路径上所有端口全部遍历完
			} catch (IOException e) {
				log.error("Failure writing flow mod", e);
			}
		}

		return packetOutSent;
	}
	
	/**
	 * Pushes a packet-out to a switch. The assumption here is that
	 * the packet-in was also generated from the same switch. Thus, if the input
	 * port of the packet-in and the outport are the same, the function will not
	 * push the packet-out.
	 * @param sw switch that generated the packet-in, and from which packet-out is sent
	 * @param pi packet-in
	 * @param outport output port
	 * @param useBufferedPacket use the packet buffered at the switch, if possible
	 * @param cntx context of the packet
	 */
	//将要发的数据发到目的节点
	protected void pushPacket(IOFSwitch sw, OFPacketIn pi, OFPort outport, boolean useBufferedPacket, FloodlightContext cntx) {
		if (pi == null) {
			return;
		}
		
		// jian 2016 - 5 - 6
		/** log.info("flow entry , switch={} , Outport = {}",sw.getId(), outport); 	*/
		// The assumption here is (sw) is the switch that generated the
		// packet-in. If the input port is the same as output port, then
		// the packet-out should be ignored.
		//如果进出端口相同，则直接返回
		if ((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)).equals(outport)) {
			if (log.isDebugEnabled()) {
				log.debug("Attempting to do packet-out to the same " +
						"interface as packet-in. Dropping packet. " +
						" SrcSwitch={}, pi={}",
						new Object[]{sw, pi});
				return;
			}
		}

		if (log.isTraceEnabled()) {
			log.trace("PacketOut srcSwitch={} pi={}",
					new Object[] {sw, pi});
		}

		// 构建packet out 消息
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		// 设置action
		List<OFAction> actions = new ArrayList<OFAction>();
		actions.add(sw.getOFFactory().actions().output(outport, Integer.MAX_VALUE));
		pob.setActions(actions);

		/* Use packet in buffer if there is a buffer ID set */
		// 是否填写buffer id
		// 如果使用packet in 中的buffer id，则交换机执行时，转发自身缓存的用户报文
		// 如果设为 NO_BUFFER(0xFFFFFFFF,即-1），交换机转发packet out消息中的报文
		if (useBufferedPacket) {
			pob.setBufferId(pi.getBufferId()); /* will be NO_BUFFER if there isn't one */
		} else {
			pob.setBufferId(OFBufferId.NO_BUFFER);
		}
       // 控制器自定义交换机及转发的报文
		if (pob.getBufferId().equals(OFBufferId.NO_BUFFER)) {
			byte[] packetData = pi.getData();
			pob.setData(packetData);
		}
        // 设置入端口
		pob.setInPort((pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT)));
	
		try {
			pob.getType();						
			messageDamper.write(sw, pob.build()); // 下发至交换机
		} catch (IOException e) {
			log.error("Failure writing packet out", e);
		}
	}

	/**
	 * Write packetout message to sw with output actions to one or more
	 * output ports with inPort/outPorts passed in.
	 * @param packetData
	 * @param sw
	 * @param inPort
	 * @param ports
	 * @param cntx
	 */
	// 将packet out消息连同操作actions写到交换机的出端口
	public void packetOutMultiPort(byte[] packetData, IOFSwitch sw, 
			OFPort inPort, Set<OFPort> outPorts, FloodlightContext cntx) {
		//setting actions
		List<OFAction> actions = new ArrayList<OFAction>();

		Iterator<OFPort> j = outPorts.iterator();

		while (j.hasNext()) {
			actions.add(sw.getOFFactory().actions().output(j.next(), 0));
		}

		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut();
		pob.setActions(actions);

		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(inPort);

		pob.setData(packetData);

		try {
			if (log.isTraceEnabled()) {
				log.trace("write broadcast packet on switch-id={} " +
						"interfaces={} packet-out={}",
						new Object[] {sw.getId(), outPorts, pob.build()});
			}
			messageDamper.write(sw, pob.build());

		} catch (IOException e) {
			log.error("Failure writing packet out", e);
		}
	}

	/**
	 * @see packetOutMultiPort
	 * Accepts a PacketIn instead of raw packet data. Note that the inPort
	 * and switch can be different than the packet in switch/port
	 */
	// 得到一个PacketIn包，可能（入端口号，交换机）是已经被修改过的packet
	public void packetOutMultiPort(OFPacketIn pi, IOFSwitch sw,
			OFPort inPort, Set<OFPort> outPorts, FloodlightContext cntx) {
		packetOutMultiPort(pi.getData(), sw, inPort, outPorts, cntx);
	}

	/**
	 * @see packetOutMultiPort
	 * Accepts an IPacket instead of raw packet data. Note that the inPort
	 * and switch can be different than the packet in switch/port
	 */
	// 得到一个IPacket包，可能（入端口号，交换机）是已经被修改过的packet
	// net.floodlightcontroller.packet   位置 IPacket.java   BasePacket.java
	public void packetOutMultiPort(IPacket packet, IOFSwitch sw,
			OFPort inPort, Set<OFPort> outPorts, FloodlightContext cntx) {
		packetOutMultiPort(packet.serialize(), sw, inPort, outPorts, cntx);
	}

	public static boolean blockHost(IOFSwitchService switchService,
			SwitchPort sw_tup, MacAddress host_mac, short hardTimeout, U64 cookie) {

		if (sw_tup == null) {
			return false;
		}

		IOFSwitch sw = switchService.getSwitch(sw_tup.getSwitchDPID());
		if (sw == null) {
			return false;
		}

		OFPort inputPort = sw_tup.getPort();
		log.debug("blockHost sw={} port={} mac={}",
				new Object[] { sw, sw_tup.getPort(), host_mac.getLong() });

		// Create flow-mod based on packet-in and src-switch
		OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd();

		Match.Builder mb = sw.getOFFactory().buildMatch();
		List<OFAction> actions = new ArrayList<OFAction>(); // Set no action to drop
		mb.setExact(MatchField.IN_PORT, inputPort);
		if (host_mac.getLong() != -1L) {
			mb.setExact(MatchField.ETH_SRC, host_mac);
		}

		fmb.setCookie(cookie)
		.setHardTimeout(hardTimeout)
		.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
		.setPriority(FLOWMOD_DEFAULT_PRIORITY)
		.setBufferId(OFBufferId.NO_BUFFER)
		.setMatch(mb.build());
		
		FlowModUtils.setActions(fmb, actions, sw);

		log.debug("write drop flow-mod sw={} match={} flow-mod={}",
					new Object[] { sw, mb.build(), fmb.build() });
		// TODO: can't use the message damper since this method is static
		sw.write(fmb.build());
		
		return true;
	}

	@Override
	// 转发模块处理 of 消息，必须在拓扑管理模块，以及设备管理模块之后
	public boolean isCallbackOrderingPrereq(OFType type, String name) {
		return (type.equals(OFType.PACKET_IN) && (name.equals("topology") || name.equals("devicemanager")));
	}

	@Override
	public boolean isCallbackOrderingPostreq(OFType type, String name) {
		return false;
	}
}