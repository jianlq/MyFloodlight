package net.floodlightcontroller.sflowcollector;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.FloodlightModuleLoader;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.output.Output;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.Null;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;
;
public class IfIndexCollector implements IFloodlightModule, IIfIndexCollectionService, IOFSwitchListener {
	public static final String oidIfDescr = "1.3.6.1.2.1.2.2.1.2";
	public static final String snmpAddrPropStr = "net.floodlightcontroller.sflowcollector.IfIndexCollector.address";
	public static final String snmpCommPropStr = "net.floodlightcontroller.sflowcollector.IfIndexCollector.community";
	
	protected IFloodlightProviderService floodlightProvider;
	protected List<String> agentIps;
	protected Map<String, Map<Integer, String>> snmpAgentIfIndexIfNameMap;
	protected Map<String,Set<String>> agentIfNamesMap;
	protected String addr;
	protected String comm;
	protected static Logger logger;
	protected Output ifNameTxt;

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IIfIndexCollectionService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
        // We are the class that implements the service
        m.put(IIfIndexCollectionService.class, this);
        return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		snmpAgentIfIndexIfNameMap = new ConcurrentHashMap<String, Map<Integer, String>>();
		agentIfNamesMap=new ConcurrentHashMap<String, Set<String>>() ;
//		ifIndexNameMap = new ConcurrentHashMap<Integer, String>();
		agentIps = Collections.synchronizedList(new ArrayList<String>());
		logger = LoggerFactory.getLogger(IfIndexCollector.class);
		ifNameTxt=new Output("ifNameTxt.txt");
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		Properties prop = new Properties();
		InputStream is = this.getClass().getClassLoader().
                getResourceAsStream(FloodlightModuleLoader.COMPILED_CONF_FILE);
		try {
			prop.load(is);
		} catch (IOException e) {
			logger.error("Could not load SNMP ADDRESS configuration file", e);
			System.exit(1);
		}
		/*boolean enabled = Boolean.parseBoolean(prop.getProperty(ISflowCollectionService.enabledPropStr, "false"));
		if(!enabled) {
			logger.info("IfindexCollector Not Enabled.");
			return;
		}*/
		addr = prop.getProperty(snmpAddrPropStr);
		comm = prop.getProperty(snmpCommPropStr);
		if(addr == null || comm == null) {
			logger.error("Could not load SNMP ADDRESS configuration file");
        	return;
		}
		floodlightProvider.addOFSwitchListener(this);
	}
	
	private void snmpWalk(Map<Integer, String> ifIndexNameMap,Set<String>ifNames, String addr, String comm, String agentIp) {
		ifNames.clear();
		addr = addr.replace("{agent_ip}", agentIp);
		Address targetAddress = GenericAddress.parse(addr);
		CommunityTarget target = new CommunityTarget();
		target.setCommunity(new OctetString(comm));
		target.setAddress(targetAddress);
		target.setTimeout(2000);
		target.setRetries(3);
		target.setVersion(SnmpConstants.version1);
		
		// creating PDU
		OID targetOID = new OID(oidIfDescr);
		PDU requestPDU = new PDU();
		requestPDU.setType(PDU.GETNEXT);
		requestPDU.add(new VariableBinding(targetOID));
  
		Snmp snmp = null;
		TransportMapping<UdpAddress> transport = null;
		try {
			transport = new DefaultUdpTransportMapping();
			snmp = new Snmp(transport);
			transport.listen();
  
			boolean finished = false;
			
			while (!finished) {
				ResponseEvent response = snmp.send(requestPDU, target);
				VariableBinding vb = null;
				if (response == null) {
					logger.error("response == null");
			
					finished = true;
					break;
				}
				
				PDU responsePDU = response.getResponse();
				if (responsePDU == null) {
					logger.error("responsePDU == null");
					
					finished = true;
					break;
				} else {
					vb = responsePDU.get(0);
				}
				finished = checkWalkFinished(targetOID, responsePDU, vb);
				if (!finished) {
					// Dump response.
					OID responseOid = vb.getOid();
					if(!ifIndexNameMap.containsKey(responseOid.last()))
					     ifIndexNameMap.put(responseOid.last(), vb.getVariable().toString());
					    // System.err.println("ifname "+vb.getVariable().toString());
					// Set up the variable binding for the next entry.
					requestPDU.setRequestID(new Integer32(0));
					requestPDU.set(0, vb);
				}

				
			}
			
			logger.info("success finish snmp walk!");
			
//			System.out.println(ifIndexNameMap);
//			Object[] a = ifIndexNameMap.keySet().toArray();
//			Arrays.sort(a);
//			System.out.println(a);
		} catch (IOException e) {
			logger.error("SNMP walk Exception: " + e);
			
			
		} finally {
			if (snmp != null) {
				try {
					snmp.close();
				} catch (IOException ex1) {
					snmp = null;
				}
			}
			if (transport != null) {
				try {
					transport.close();
				} catch (IOException ex2) {
					transport = null;
				}
			}
		}
	}
	
	/**
	 * check snmp walk finish
	 * @param resquestPDU
	 * @param targetOID
	 * @param responsePDU
	 * @param vb
	 * @return
	 */
	private boolean checkWalkFinished(OID targetOID, PDU responsePDU,
			VariableBinding vb) {
		boolean finished = false;
		if (responsePDU.getErrorStatus() != 0) {
			logger.error("responsePDU.getErrorStatus() != 0 ");
			logger.error(responsePDU.getErrorStatusText());
			finished = true;
		} else if (vb.getOid() == null) {
			logger.error("vb.getOid() == null");
			finished = true;
		} else if (vb.getOid().size() < targetOID.size()) {
			logger.error("vb.getOid().size() < targetOID.size()");
			finished = true;
		} else if (targetOID.leftMostCompare(targetOID.size(), vb.getOid()) != 0) {
			logger.info("targetOID.leftMostCompare() != 0");
			finished = true;
		} else if (Null.isExceptionSyntax(vb.getVariable().getSyntax())) {
			logger.error("Null.isExceptionSyntax(vb.getVariable().getSyntax())");
			finished = true;
		} else if (vb.getOid().compareTo(targetOID) <= 0) {
			logger.error("Variable received is not "
					+ "lexicographic successor of requested " + "one:");
			logger.error(vb.toString() + " <= " + targetOID);
			finished = true;
		}
		return finished;
	}
	
	@Override
	public String getIfNameByIfIndex(String snmpAgentIp, Integer ifIndex) {
		String name = null;
		if(snmpAgentIfIndexIfNameMap != null && snmpAgentIfIndexIfNameMap.containsKey(snmpAgentIp)) {
			Map<Integer, String> ifIndexIfNameMap = snmpAgentIfIndexIfNameMap.get(snmpAgentIp);
			if(ifIndexIfNameMap != null && ifIndexIfNameMap.containsKey(ifIndex)) {
				
				name = ifIndexIfNameMap.get(ifIndex);	
				
				//iftxt.writeData("index:"+ifIndex+"   name:"+name);
			}
			if(!ifIndexIfNameMap.containsKey(ifIndex)){
			  //  snmpWalk(snmpAgentIfIndexIfNameMap.get(snmpAgentIp), agentIfNamesMap.get(snmpAgentIp),addr, comm, snmpAgentIp);
			    //ifNameTxt.writeData("run snmp again");
				//name = ifIndexIfNameMap.get(ifIndex);
				
			}
		}
		
		return name;
	}

	@Override
	public void addedSwitch(IOFSwitch sw) {
		ifNameTxt.writeData("new sw");
		SocketAddress sa = sw.getInetAddress();
		if(sa instanceof InetSocketAddress) {                                                    
			InetSocketAddress isa = (InetSocketAddress) sa;
			String ip = isa.getAddress().getHostAddress();
			//Every new come agent.
			if(!agentIps.contains(ip)) {
				agentIps.add(ip);
				if(!snmpAgentIfIndexIfNameMap.containsKey(ip)) {
					snmpAgentIfIndexIfNameMap.put(ip, new HashMap<Integer, String>());
					agentIfNamesMap.put(ip,new HashSet<String>());
					
				}
				ifNameTxt.writeData("start to snmpwalk");
				snmpWalk(snmpAgentIfIndexIfNameMap.get(ip),agentIfNamesMap.get(ip), addr, comm, ip); 
			}
			//snmpWalk(snmpAgentIfIndexIfNameMap.get(ip),agentIfNamesMap.get(ip), addr, comm, ip); 
		}
	}

	@Override
	public void removedSwitch(IOFSwitch sw) {
		//We don't need to process this situation
//		SocketAddress sa = sw.getInetAddress();
//		if(sa instanceof InetSocketAddress) {
//			InetSocketAddress isa = (InetSocketAddress) sa;
//			String ip = isa.getAddress().getHostAddress();
//			if(agentIps.contains(ip)) {
//				agentIps.remove(ip);
//				for(IOFSwitch s : floodlightProvider.getSwitches().values()) {
//					SocketAddress sa1 = s.getInetAddress();
//					if(sa instanceof InetSocketAddress) {
//						InetSocketAddress isa1 = (InetSocketAddress) sa1;
//						String ip1 = isa1.getAddress().getHostAddress();
//						if(ip1.equals(ip) && !agentIps.contains(ip1)) {
//							agentIps.add(ip1);
//						}
//					}
//				}
//			}
//			ifIndexNameMap.clear();
//			for(String agentIP : agentIps) {
//				snmpWalk(ifIndexNameMap, addr, comm, agentIP);
//			}
//		}
	}

	@Override
	public void switchPortChanged(Long switchId) {
		//this don't need to process either
		//		ifIndexNameMap.clear();
//		for(String agentIP : agentIps) {
//			snmpWalk(ifIndexNameMap, addr, comm, agentIP);
//		}
	}

	@Override
	public String getName() {
		return "IfIndexCollector";
	}

	@Override
	public Set<String> returnIfnames(String snmpAgentIp) {
		
		return agentIfNamesMap.get(snmpAgentIp);
	}

}
