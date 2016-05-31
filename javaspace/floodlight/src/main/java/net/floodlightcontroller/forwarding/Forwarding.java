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

package net.floodlightcontroller.forwarding;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.floodlightcontroller.core.FloodlightContext;
import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.PortChangeType;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.core.util.AppCookie;
import net.floodlightcontroller.debugcounter.IDebugCounterService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.devicemanager.SwitchPort;
import net.floodlightcontroller.packet.Ethernet;
import net.floodlightcontroller.packet.IPv4;
import net.floodlightcontroller.packet.IPv6;
import net.floodlightcontroller.packet.TCP;
import net.floodlightcontroller.packet.UDP;
import net.floodlightcontroller.routing.ForwardingBase;
import net.floodlightcontroller.routing.IRoutingDecision;
import net.floodlightcontroller.routing.IRoutingService;
import net.floodlightcontroller.routing.Route;
import net.floodlightcontroller.statistics.IStatisticsService;
import net.floodlightcontroller.statistics.SwitchPortBandwidth;
import net.floodlightcontroller.topology.ITopologyService;
import net.floodlightcontroller.topology.NodePortTuple;
import net.floodlightcontroller.util.FlowModUtils;
import net.floodlightcontroller.util.OFDPAUtils;
import net.floodlightcontroller.util.OFPortMode;
import net.floodlightcontroller.util.OFPortModeTuple;

import org.projectfloodlight.openflow.protocol.OFFlowMod;
import org.projectfloodlight.openflow.protocol.OFFlowModCommand;
import org.projectfloodlight.openflow.protocol.OFGroupType;
import org.projectfloodlight.openflow.protocol.OFPacketIn;
import org.projectfloodlight.openflow.protocol.OFPacketOut;
import org.projectfloodlight.openflow.protocol.OFPortDesc;
import org.projectfloodlight.openflow.protocol.OFType;
import org.projectfloodlight.openflow.protocol.OFVersion;
import org.projectfloodlight.openflow.protocol.action.OFAction;
import org.projectfloodlight.openflow.protocol.match.Match;
import org.projectfloodlight.openflow.protocol.match.MatchField;
import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.EthType;
import org.projectfloodlight.openflow.types.IPv4Address;
import org.projectfloodlight.openflow.types.IPv6Address;
import org.projectfloodlight.openflow.types.IpProtocol;
import org.projectfloodlight.openflow.types.MacAddress;
import org.projectfloodlight.openflow.types.OFBufferId;
import org.projectfloodlight.openflow.types.OFGroup;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.OFVlanVidMatch;
import org.projectfloodlight.openflow.types.TableId;
import org.projectfloodlight.openflow.types.U64;
import org.projectfloodlight.openflow.types.VlanVid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// ForwardingBase 在net.floodlightcontroller.routing中
public class Forwarding extends ForwardingBase implements IFloodlightModule, IOFSwitchListener {
	protected static Logger log = LoggerFactory.getLogger(Forwarding.class);

	@Override
//All subclasses must define this function if they want any specific forwarding action
//Overrides: processPacketInMessage(...) in ForwardingBase
//Parameters:
//	sw Switch that the packet came in from ：发packet_in消息的交换机
//	pi The packet that came in ：具体的packet_in消息
//	decision Any decision made by a policy engine ：策略引擎的决定
//	cntx ： 模块上下文
	
	public Command processPacketInMessage(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		// bsStore : 得到packet_in 的payload（有效载荷） 一个 IP 数据包通常包含 header (报头信息) 和 payload (有效载荷)。
	    //payload 中的内容即是要传输的真正信息，而 header 承载的是与传输数据有关的元数据 (metadata)。
		//  We found a routing decision (i.e. Firewall is enabled... it's the only thing that makes RoutingDecisions) 
		// 路由决策来自Firewall模块，如果开启了Firewall则有相应的路由决策；默认Firewall不开启，故这里没有路由决策
		if (decision != null) {
			if (log.isTraceEnabled()) {
				log.trace("Forwarding decision={} was made for PacketIn={}", decision.getRoutingAction().toString(), pi);
			} 		
			// 根据路由决策decision分情况处理
			switch(decision.getRoutingAction()) {
			case NONE:   
				// don't do anything
				return Command.CONTINUE;
			case FORWARD_OR_FLOOD:
			case FORWARD:    //转发
				doForwardFlow(sw, pi, cntx, false);
				return Command.CONTINUE;
			case MULTICAST:    //多播
				// treat as broadcast
				doFlood(sw, pi, cntx);
				return Command.CONTINUE;
			case DROP:      //丢弃
				doDropFlow(sw, pi, decision, cntx);
				return Command.CONTINUE;
			default:
				log.error("Unexpected decision made for this packet-in={}", pi, decision.getRoutingAction());
				return Command.CONTINUE;
			}
		} else {    // 没有路由决策，则转发到目的设备或者洪泛             No routing decision was found. Forward to destination or flood if bcast or mcast.
			if (log.isTraceEnabled()) {
				log.trace("No decision was made for PacketIn={}, forwarding", pi);
			}
			if (eth.isBroadcast() || eth.isMulticast()) {
				doFlood(sw, pi, cntx); //如果目的地址是广播和多播地址，就进行洪泛    ff：ff：ff：ff 广播     
				// IEEE 802.3规定：以太网的第48bit 用于表示这个地址是组播地址还是单播地址。
				// 如果这一位是0，表示此MAC地址是单播地址，如果这位是1，表示此MAC地址是多播地址
			} else {
				doForwardFlow(sw, pi, cntx, false);		
			}
		}
		//this.getBandwidthConsumptionInForwarding();
		return Command.CONTINUE;
	}

	protected void doDropFlow(IOFSwitch sw, OFPacketIn pi, IRoutingDecision decision, FloodlightContext cntx) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		Match m = createMatchFromPacket(sw, inPort, cntx);
		OFFlowMod.Builder fmb = sw.getOFFactory().buildFlowAdd(); // this will be a drop-flow; a flow that will not output to any ports
		List<OFAction> actions = new ArrayList<OFAction>(); // set no action to drop
		U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);//得到模块的ID号
		log.info("Droppingggg");
		fmb.setCookie(cookie)
		.setHardTimeout(FLOWMOD_DEFAULT_HARD_TIMEOUT)
		.setIdleTimeout(FLOWMOD_DEFAULT_IDLE_TIMEOUT)
		.setBufferId(OFBufferId.NO_BUFFER)
		.setMatch(m)
		.setPriority(FLOWMOD_DEFAULT_PRIORITY);
		
		FlowModUtils.setActions(fmb, actions, sw);

		try {
			if (log.isDebugEnabled()) {
				log.debug("write drop flow-mod sw={} match={} flow-mod={}",
						new Object[] { sw, m, fmb.build() });
			}
			boolean dampened = messageDamper.write(sw, fmb.build());
			log.debug("OFMessage dampened: {}", dampened);
		} catch (IOException e) {
			log.error("Failure writing drop flow mod", e);
		}
	}
    /*
     doForwardFlow()的最后一个参数为 false，即自动下发的流表项在交换机上到期删除时，不必向 floodlight 回复流表项删除消息。
     */
	protected void doForwardFlow(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx, boolean requestFlowRemovedNotifn) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		//获得目标设备（用户请求的主机）
		IDevice dstDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_DST_DEVICE);
		DatapathId source = sw.getId();
		
		if (dstDevice != null) {
			IDevice srcDevice = IDeviceService.fcStore.get(cntx, IDeviceService.CONTEXT_SRC_DEVICE);

			if (srcDevice == null) {
				log.error("No device entry found for source device. Is the device manager running? If so, report bug.");
				return;
			}
			
			if (FLOOD_ALL_ARP_PACKETS && 
					IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD).getEtherType() 
					== EthType.ARP) {
				log.debug("ARP flows disabled in Forwarding. Flooding ARP packet");
				doFlood(sw, pi, cntx);
				return;
			}

			/* Validate that the source and destination are not on the same switch port */
			boolean on_same_if = false;
			for (SwitchPort dstDap : dstDevice.getAttachmentPoints()) {
				if (sw.getId().equals(dstDap.getSwitchDPID()) && inPort.equals(dstDap.getPort())) {
					on_same_if = true;
				}
				break;
			}

			if (on_same_if) {
				log.info("Both source and destination are on the same switch/port {}/{}. Action = NOP", sw.toString(), inPort);
				return;
			}
	   // 一个重要的地方是设备附件（连接）点，如果见环境交换机上接收到一个包，一个连接点就会为这个设备创建。一个设备在每个Openflow island上可以有一个或者多个连接点。
	  //OpenFlow Island定义为与相同Floodlight Controller交互的一组OpenFlow的强连接集合
			SwitchPort[] dstDaps = dstDevice.getAttachmentPoints();
			SwitchPort dstDap = null;

			/* 
			 * Search for the true attachment point. The true AP is
			 * not an endpoint of a link. It is a switch port w/o an
			 * associated link. Note this does not necessarily hold
			 * true for devices that 'live' between OpenFlow islands.
			 * 
			 * TODO Account for the case where a device is actually
			 * attached between islands (possibly on a non-OF switch
			 * in between two OpenFlow switches).
			 */
			for (SwitchPort ap : dstDaps) {
				if (topologyService.isEdge(ap.getSwitchDPID(), ap.getPort())) {
					dstDap = ap;
					break;
				}
			}	

			/* 
			 * This should only happen (perhaps) when the controller is
			 * actively learning a new topology and hasn't discovered
			 * all links yet, or a switch was in standalone mode and the
			 * packet in question was captured in flight on the dst point
			 * of a link.
			 */
			if (dstDap == null) {
				log.warn("Could not locate edge attachment point for device {}. Flooding packet");
				doFlood(sw, pi, cntx);
				return; 
			}
			
			/* It's possible that we learned packed destination while it was in flight */
			if (!topologyService.isEdge(source, inPort)) {	
				log.debug("Packet destination is known, but packet was not received on an edge port (rx on {}/{}). Flooding packet", source, inPort);
				doFlood(sw, pi, cntx);
				return; 
			}				
			// 获得两个端口之间的路由 route，参数为源设备，入端口，目标设备，目标端口，cookie
			// getRoute( )来自IRoutingService中，在TopologyManager中行712 ，实现IRoutingService服务，真正的代码实现在TopologyInstance 行 871 中
			Route route = routingEngineService.getRoute(source, 
					inPort,
					dstDap.getSwitchDPID(),
					dstDap.getPort(), U64.of(0)); //cookie = 0, i.e., default route

			Match m = createMatchFromPacket(sw, inPort, cntx);
			U64 cookie = AppCookie.makeCookie(FORWARDING_APP_ID, 0);//用来标识app的cookie
			
			if (route != null) {
				log.debug("pushRoute inPort={} route={} " +
						"destination={}:{}",
						new Object[] { inPort, route,
						dstDap.getSwitchDPID(),
						dstDap.getPort()});

				//  调用pushRoute方法向交换机下发流表
				log.debug("Cretaing flow rules on the route, match rule: {}", m);
				
				//  jian   //
				/** log.info("      Route   size = {}",route.getPath().size() );   	
				*/
				pushRoute(route, m, pi, sw.getId(), cookie, 
						cntx, requestFlowRemovedNotifn,
						OFFlowModCommand.ADD);	
			} else {
				/* Route traverses no links --> src/dst devices on same switch */
				log.debug("Could not compute route. Devices should be on same switch src={} and dst={}", srcDevice, dstDevice);
				Route r = new Route(srcDevice.getAttachmentPoints()[0].getSwitchDPID(), dstDevice.getAttachmentPoints()[0].getSwitchDPID());
				List<NodePortTuple> path = new ArrayList<NodePortTuple>(2);
				path.add(new NodePortTuple(srcDevice.getAttachmentPoints()[0].getSwitchDPID(),
						srcDevice.getAttachmentPoints()[0].getPort()));
				path.add(new NodePortTuple(dstDevice.getAttachmentPoints()[0].getSwitchDPID(),
						dstDevice.getAttachmentPoints()[0].getPort()));
				r.setPath(path);
				pushRoute(r, m, pi, sw.getId(), cookie,
						cntx, requestFlowRemovedNotifn,
						OFFlowModCommand.ADD);
			}
		}  // end of first if （行179）
		else {   // 没有找到目标设备，进行洪泛
			log.debug("Destination unknown. Flooding packet");
			doFlood(sw, pi, cntx);
		}
	}

	/**
	 * Instead of using the Firewall's routing decision Match, which might be as general
	 * as "in_port" and inadvertently Match packets erroneously, construct a more
	 * specific Match based on the deserialized OFPacketIn's payload, which has been 
	 * placed in the FloodlightContext already by the Controller.
	 * 
	 * @param sw, the switch on which the packet was received
	 * @param inPort, the ingress switch port on which the packet was received
	 * @param cntx, the current context which contains the deserialized packet //deserialized 反序列化
	 * @return a composed Match object based on the provided information
	 */
	protected Match createMatchFromPacket(IOFSwitch sw, OFPort inPort, FloodlightContext cntx) {
		// The packet in match will only contain the port number.
		// We need to add in specifics for the hosts we're routing between.
		// 根据packet in的数据负载构建匹配项
		Ethernet eth = IFloodlightProviderService.bcStore.get(cntx, IFloodlightProviderService.CONTEXT_PI_PAYLOAD);
		VlanVid vlan = VlanVid.ofVlan(eth.getVlanID());
		MacAddress srcMac = eth.getSourceMACAddress();
		MacAddress dstMac = eth.getDestinationMACAddress();
       
		// setExact 设置确切的匹配中（入端口，mac地址，ip地址，tcp端口号）的相应的值
		Match.Builder mb = sw.getOFFactory().buildMatch();
		mb.setExact(MatchField.IN_PORT, inPort);//匹配域

		if (FLOWMOD_DEFAULT_MATCH_MAC) {
			mb.setExact(MatchField.ETH_SRC, srcMac)
			.setExact(MatchField.ETH_DST, dstMac);
		}

		if (FLOWMOD_DEFAULT_MATCH_VLAN) {
			if (!vlan.equals(VlanVid.ZERO)) {
				mb.setExact(MatchField.VLAN_VID, OFVlanVidMatch.ofVlanVid(vlan));
			}
		}

		// TODO Detect switch type and match to create hardware-implemented flow
		if (eth.getEtherType() == EthType.IPv4) { /* shallow check for equality is okay for EthType */
			IPv4 ip = (IPv4) eth.getPayload();
			IPv4Address srcIp = ip.getSourceAddress();
			IPv4Address dstIp = ip.getDestinationAddress();
			
			if (FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
				mb.setExact(MatchField.ETH_TYPE, EthType.IPv4)
				.setExact(MatchField.IPV4_SRC, srcIp)
				.setExact(MatchField.IPV4_DST, dstIp);
			}

			if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
				/*
				 * Take care of the ethertype if not included earlier,
				 * since it's a prerequisite for transport ports.
				 */
				if (!FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
					mb.setExact(MatchField.ETH_TYPE, EthType.IPv4);
				}
				
				if (ip.getProtocol().equals(IpProtocol.TCP)) {
					TCP tcp = (TCP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
					.setExact(MatchField.TCP_SRC, tcp.getSourcePort())
					.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
				} else if (ip.getProtocol().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
					.setExact(MatchField.UDP_SRC, udp.getSourcePort())
					.setExact(MatchField.UDP_DST, udp.getDestinationPort());
				}
			}
		} else if (eth.getEtherType() == EthType.ARP) { /* shallow check for equality is okay for EthType */
			mb.setExact(MatchField.ETH_TYPE, EthType.ARP);
		} else if (eth.getEtherType() == EthType.IPv6) {
			IPv6 ip = (IPv6) eth.getPayload();
			IPv6Address srcIp = ip.getSourceAddress();
			IPv6Address dstIp = ip.getDestinationAddress();
			
			if (FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
				mb.setExact(MatchField.ETH_TYPE, EthType.IPv6)
				.setExact(MatchField.IPV6_SRC, srcIp)
				.setExact(MatchField.IPV6_DST, dstIp);
			}

			if (FLOWMOD_DEFAULT_MATCH_TRANSPORT) {
				/*
				 * Take care of the ethertype if not included earlier,
				 * since it's a prerequisite for transport ports.
				 */
				if (!FLOWMOD_DEFAULT_MATCH_IP_ADDR) {
					mb.setExact(MatchField.ETH_TYPE, EthType.IPv6);
				}
				
				if (ip.getNextHeader().equals(IpProtocol.TCP)) {
					TCP tcp = (TCP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.TCP)
					.setExact(MatchField.TCP_SRC, tcp.getSourcePort())
					.setExact(MatchField.TCP_DST, tcp.getDestinationPort());
				} else if (ip.getNextHeader().equals(IpProtocol.UDP)) {
					UDP udp = (UDP) ip.getPayload();
					mb.setExact(MatchField.IP_PROTO, IpProtocol.UDP)
					.setExact(MatchField.UDP_SRC, udp.getSourcePort())
					.setExact(MatchField.UDP_DST, udp.getDestinationPort());
				}
			}
		}
		return mb.build();
	}

	/**
	 * Creates a OFPacketOut with the OFPacketIn data that is flooded on all ports unless
	 * the port is blocked, in which case the packet will be dropped.
	 * @param sw The switch that receives the OFPacketIn
	 * @param pi The OFPacketIn that came to the switch
	 * @param cntx The FloodlightContext associated with this OFPacketIn
	 */
	protected void doFlood(IOFSwitch sw, OFPacketIn pi, FloodlightContext cntx) {
		OFPort inPort = (pi.getVersion().compareTo(OFVersion.OF_12) < 0 ? pi.getInPort() : pi.getMatch().get(MatchField.IN_PORT));
		// Set Action to flood
		OFPacketOut.Builder pob = sw.getOFFactory().buildPacketOut(); // getOFFactory()  Returns a factory object that can be used to create OpenFlow messages.
		List<OFAction> actions = new ArrayList<OFAction>();
		Set<OFPort> broadcastPorts = this.topologyService.getSwitchBroadcastPorts(sw.getId()); // getId() Get the datapathId of the switch

		if (broadcastPorts == null) {
			log.debug("BroadcastPorts returned null. Assuming single switch w/no links.");
			/* Must be a single-switch w/no links */
			broadcastPorts = Collections.singleton(OFPort.FLOOD);
		}
		
		for (OFPort p : broadcastPorts) {
			if (p.equals(inPort)) continue;
			actions.add(sw.getOFFactory().actions().output(p, Integer.MAX_VALUE));
		}
		pob.setActions(actions);
		// log.info("actions {}",actions);
		// set buffer-id, in-port and packet-data based on packet-in
		pob.setBufferId(OFBufferId.NO_BUFFER);
		pob.setInPort(inPort);
		pob.setData(pi.getData());

		try {
			if (log.isTraceEnabled()) {
				log.trace("Writing flood PacketOut switch={} packet-in={} packet-out={}",
						new Object[] {sw, pi, pob.build()});
			}
			messageDamper.write(sw, pob.build());
		} catch (IOException e) {
			log.error("Failure writing PacketOut switch={} packet-in={} packet-out={}",
					new Object[] {sw, pi, pob.build()}, e);
		}

		return;
	}

	// IFloodlightModule methods

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		// We don't export any services
		return null;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService>
	getServiceImpls() {
		// We don't have any services
		return null;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IDeviceService.class);
		l.add(IRoutingService.class);
		l.add(ITopologyService.class);
		l.add(IDebugCounterService.class);
		l.add(IStatisticsService.class); // jian
		return l;
	}

	@Override
	// @param FloodlightModuleContext 模块上下文，服务登记处/注册表
	public void init(FloodlightModuleContext context) throws FloodlightModuleException {
		super.init();// 父类初始化
		//  IFloodlightService s = serviceMap.get(service); 得到服务映射表
		// 得到各个依赖的服务
		this.floodlightProviderService = context.getServiceImpl(IFloodlightProviderService.class);
		this.deviceManagerService = context.getServiceImpl(IDeviceService.class);
		this.routingEngineService = context.getServiceImpl(IRoutingService.class);
		this.topologyService = context.getServiceImpl(ITopologyService.class);
		this.debugCounterService = context.getServiceImpl(IDebugCounterService.class);
		this.switchService = context.getServiceImpl(IOFSwitchService.class);
		this.statisticsService = context.getServiceImpl(IStatisticsService.class); // jian

		//Gets module specific configuration parameters。检查配置文件是否设置了流表项的配置信息
		// hard-timeout ：流表项的存在时间，即流表项自创建的时刻开始，经过 hard time 后将会自动删除；
		//idle-timeout ：流表项的空闲超时时间，即数据包在没有匹配后的 idle time 后，流表项将自动删除；
		//priority：流表项优先级。数字越大表示优先级越高
		Map<String, String> configParameters = context.getConfigParams(this);
		String tmp = configParameters.get("hard-timeout"); //硬超时
		//得到键值为 hard-timeout的value值
		if (tmp != null) {
			FLOWMOD_DEFAULT_HARD_TIMEOUT = Integer.parseInt(tmp);//parseInt字符串转整数
			log.info("Default hard timeout set to {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		} else {
			log.info("Default hard timeout not configured. Using {}.", FLOWMOD_DEFAULT_HARD_TIMEOUT);
		}
		tmp = configParameters.get("idle-timeout");// 空闲时间
		if (tmp != null) {
			FLOWMOD_DEFAULT_IDLE_TIMEOUT = Integer.parseInt(tmp);
			log.info("Default idle timeout set to {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		} else {
			log.info("Default idle timeout not configured. Using {}.", FLOWMOD_DEFAULT_IDLE_TIMEOUT);
		}
		tmp = configParameters.get("priority"); //优先级
		if (tmp != null) {
			FLOWMOD_DEFAULT_PRIORITY = 35777;
			log.info("Default priority set to {}.", FLOWMOD_DEFAULT_PRIORITY);
		} else {
			log.info("Default priority not configured. Using {}.", FLOWMOD_DEFAULT_PRIORITY);
		}
		tmp = configParameters.get("set-send-flow-rem-flag");//发送的流删除标志，
		if (tmp != null) {
			FLOWMOD_DEFAULT_SET_SEND_FLOW_REM_FLAG = Boolean.parseBoolean(tmp);
			log.info("Default flags will be set to SEND_FLOW_REM.");
		} else {
			log.info("Default flags will be empty.");
		}
		tmp = configParameters.get("match"); // 匹配规则  vlan， mac， ip，port
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("vlan") && !tmp.contains("mac") && !tmp.contains("ip") && !tmp.contains("port")) {
				/* leave the default configuration -- blank or invalid 'match' value */
			} else {   //设置默认匹配规则
				FLOWMOD_DEFAULT_MATCH_VLAN = tmp.contains("vlan") ? true : false;
				FLOWMOD_DEFAULT_MATCH_MAC = tmp.contains("mac") ? true : false;
				FLOWMOD_DEFAULT_MATCH_IP_ADDR = tmp.contains("ip") ? true : false;
				FLOWMOD_DEFAULT_MATCH_TRANSPORT = tmp.contains("port") ? true : false;
			}
		}
		log.info("Default flow matches set to: VLAN=" + FLOWMOD_DEFAULT_MATCH_VLAN
				+ ", MAC=" + FLOWMOD_DEFAULT_MATCH_MAC
				+ ", IP=" + FLOWMOD_DEFAULT_MATCH_IP_ADDR
				+ ", TPPT=" + FLOWMOD_DEFAULT_MATCH_TRANSPORT);	
		tmp = configParameters.get("flood-arp"); // 是否进行arp 洪泛
		if (tmp != null) {
			tmp = tmp.toLowerCase();
			if (!tmp.contains("yes") && !tmp.contains("yep") && !tmp.contains("true") && !tmp.contains("ja") && !tmp.contains("stimmt")) {
				FLOOD_ALL_ARP_PACKETS = false;
				log.info("Not flooding ARP packets. ARP flows will be inserted for known destinations");
			} else {
				FLOOD_ALL_ARP_PACKETS = true;
				log.info("Flooding all ARP packets. No ARP flows will be inserted");
			}
		}
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		super.startUp();  
		//	floodlightProviderService.addOFMessageListener(OFType.PACKET_IN, this);
		// 在父类中注册了模块care的 of 消息类型 PACKET_IN
		switchService.addOFSwitchListener(this);//监听交换机
	}

	@Override
	//Fired when switch becomes known to the controller cluster. I.e., the switch is connected at some controller in the cluster
	public void switchAdded(DatapathId switchId) {  //in IOFSwitchListener
	}

	@Override
	public void switchRemoved(DatapathId switchId) {	//in IOFSwitchListener	
	}

	@Override
	// DatapathID 64位  标识交换机  低48位是mac地址  高16位是instance ID   http://pakiti.com/datapath-ids/
	//Fired when a switch becomes active *on the local controller*, I.e., the switch is connected to the local controller and is in MASTER mode
	public void switchActivated(DatapathId switchId) {  //in IOFSwitchListener
		IOFSwitch sw = switchService.getSwitch(switchId);
		if (sw == null) {
			log.warn("Switch {} was activated but had no switch object in the switch service. Perhaps it quickly disconnected", switchId);
			return;
		}
		if (OFDPAUtils.isOFDPASwitch(sw)) {
			sw.write(sw.getOFFactory().buildFlowDelete()
					.setTableId(TableId.ALL)
					.build()
					);
			sw.write(sw.getOFFactory().buildGroupDelete()
					.setGroup(OFGroup.ANY)
					.setGroupType(OFGroupType.ALL)
					.build()
					);
			sw.write(sw.getOFFactory().buildGroupDelete()
					.setGroup(OFGroup.ANY)
					.setGroupType(OFGroupType.INDIRECT)
					.build()
					);
			sw.write(sw.getOFFactory().buildBarrierRequest().build());
			
			List<OFPortModeTuple> portModes = new ArrayList<OFPortModeTuple>();
			for (OFPortDesc p : sw.getPorts()) {
				portModes.add(OFPortModeTuple.of(p.getPortNo(), OFPortMode.ACCESS));
			}
			if (log.isWarnEnabled()) {
				log.warn("For OF-DPA switch {}, initializing VLAN {} on ports {}", new Object[] { switchId, VlanVid.ZERO, portModes});
			}
			OFDPAUtils.addLearningSwitchPrereqs(sw, VlanVid.ZERO, portModes);
		}
	}

	@Override
	public void switchPortChanged(DatapathId switchId, OFPortDesc port, PortChangeType type) {		
	}

	@Override
	public void switchChanged(DatapathId switchId) {
	}

	@Override
	public void switchDeactivated(DatapathId switchId) {
		// TODO Auto-generated method stub
		
	}
	
	// jian  2016 - 5 -20   验证使用数据收集服务   调用在 processPacketInMessage() 行142   this.getBandwidthConsumptionInForwarding()
	public void getBandwidthConsumptionInForwarding(){
		Map<NodePortTuple, SwitchPortBandwidth> bwStats = this.statisticsService.getBandwidthConsumption();
		for(NodePortTuple npt:bwStats.keySet()){	
			log.info(" forwarding switch and port {}",npt);
			log.info("Bit/s  Tx  {}    Rx is {} ",bwStats.get(npt).getBitsPerSecondTx().getValue(),bwStats.get(npt).getBitsPerSecondRx().getValue());
			log.info("Byte  Tx  {}    Rx is {} ",bwStats.get(npt).getPriorByteValueTx().getValue(),bwStats.get(npt).getPriorByteValueRx().getValue());
		}	
	}
}
