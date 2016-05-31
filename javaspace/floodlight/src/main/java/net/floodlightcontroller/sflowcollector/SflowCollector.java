package net.floodlightcontroller.sflowcollector;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.FloodlightModuleLoader;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.output.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
import org.slf4j.LoggerFactory;

public class SflowCollector implements IFloodlightModule, ISflowCollectionService {
	public static final String sflowRtUriPropStr = "net.floodlightcontroller.sflowcollector.SflowCollector.uri";
	
	public static final long DEFAULT_FIRST_DELAY = 10000L;
	public static final long DEFAULT_PERIOD = 2000L;
	protected IFloodlightProviderService floodlightProvider;
	protected IIfIndexCollectionService ifIndexCollector;
	protected Map<String, InterfaceStatistics> ifNameIfStatMap;
	protected Set<ISflowListener> sflowListeners;
	protected String sFlowRTURI;
	protected long firstDelay;
	protected long period;
	//protected static Logger logger;
	protected Output sflowCollectTxt;
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(ISflowCollectionService.class);
		return l;
	}

	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> getServiceImpls() {
		Map<Class<? extends IFloodlightService>, IFloodlightService> m =
				new HashMap<Class<? extends IFloodlightService>, IFloodlightService>();
        // We are the class that implements the service
        m.put(ISflowCollectionService.class, this);
        return m;
	}

	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleDependencies() {
		Collection<Class<? extends IFloodlightService>> l =
				new ArrayList<Class<? extends IFloodlightService>>();
		l.add(IFloodlightProviderService.class);
		l.add(IIfIndexCollectionService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
		floodlightProvider = context.getServiceImpl(IFloodlightProviderService.class);
		ifIndexCollector = context.getServiceImpl(IIfIndexCollectionService.class);
		sflowListeners = new CopyOnWriteArraySet<ISflowListener>();
		//logger = LoggerFactory.getLogger(SflowCollector.class);
		ifNameIfStatMap = new ConcurrentHashMap<String, InterfaceStatistics>();
		sflowCollectTxt=new Output("sflowCollectTxt.txt");
	}

	@Override
	public void startUp(FloodlightModuleContext context) {
		Properties prop = new Properties();
		InputStream is = this.getClass().getClassLoader().
                getResourceAsStream(FloodlightModuleLoader.COMPILED_CONF_FILE);
		try {
			prop.load(is);
		} catch (IOException e) {
		//.error("Could not load sFlow-RT URI configuration file", e);
			System.exit(1);
		}
		boolean enabled = Boolean.parseBoolean(prop.getProperty(ISflowCollectionService.enabledPropStr, "true"));
		if(!enabled) {
			System.out.println("SflowCollector Not Enabled.");
			return;
		}
		sFlowRTURI = prop.getProperty(sflowRtUriPropStr);
		if(sFlowRTURI == null || sFlowRTURI.length() == 0) {
			//logger.error("Could not load sFlow-RT URI configuration file");
			System.exit(1);
		}
		firstDelay = DEFAULT_FIRST_DELAY;
		period = DEFAULT_PERIOD;
		
		Timer t = new Timer("SflowCollectionTimer");
		t.schedule(new SflowCollectTimerTask(), firstDelay, period);
	}
	
	private void sflowCollect(String baseUri, String agentIp) {
		
		SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		String time=df.format(new Date());
		String uri = baseUri.replace("{agent_ip}", agentIp);		
		ClientResource resource = new ClientResource(uri);
	
    	Representation r = resource.get();
		
		JsonRepresentation jr = null;
		JSONObject jo = null;
		try {
			jr = new JsonRepresentation(r);
			jo = jr.getJsonObject();
		} catch (IOException e) {
			
		} catch (JSONException e) {
			
		}
		if(jo == null) {
			//logger.error("Get JSON failed.");
		}
		
		@SuppressWarnings("unchecked")
		Iterator<String> it = jo.keys();
		while(it.hasNext()) {
		//	System.err.println("the sflow data is coming");
			String key = it.next(); 
			String statProp = key.substring(key.indexOf(".") + 1);
			if(InterfaceStatistics.ALLJSONPROPERTIES.contains(statProp)) {
				Integer ifIndex = -1;
				try {
					ifIndex = Integer.parseInt(key.substring(0, key.indexOf(".")));
					
				} catch(NumberFormatException e) {
					continue;
				}
				if(ifIndex >= 0) {
					String ifName = null;
					if (ifIndexCollector != null) {
						ifName = ifIndexCollector.getIfNameByIfIndex(agentIp, ifIndex);
						
					}
					else{
						System.err.println("(ifIndexCollector = null");
					}
					if (ifName != null) {
					//	if(ifName.contains("s")&&ifName.contains("-eth")){
						    if (!ifNameIfStatMap.containsKey(ifName)) {
							     ifNameIfStatMap.put(ifName, new InterfaceStatistics(ifIndex, ifName));
							}
						    InterfaceStatistics is = ifNameIfStatMap.get(ifName);
						   // System.err.println("get the ifname data");
						    is.fromJsonProp(key, jo);					    	   
					//	}
					//	else{
							//sflowCollectTxt.writeData("ifname:"+ifName);
					//	}
						
					}
					else{
						//System.err.println("cannot get ifname from sflowdata");
					}
				}
			}   
		}
	
		
	}
	
	@Override
	public void addSflowListener(ISflowListener listener) {
		sflowListeners.add(listener);
	}
	
	@Override
	public void removeSflowListener(ISflowListener listener) {
		sflowListeners.remove(listener);
	}
	
	private class SflowCollectTimerTask extends TimerTask {

		@Override
		public void run() {	
			SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
			String time=df.format(new Date());
			sflowCollectTxt.writeData(time);
			List<String> agentIps = new ArrayList<String>();
			for(IOFSwitch sw : floodlightProvider.getSwitches().values()) {
				SocketAddress sa = sw.getInetAddress();
				if(sa instanceof InetSocketAddress) {
					InetSocketAddress isa = (InetSocketAddress) sa;
					String ip = isa.getAddress().getHostAddress();
					if(!agentIps.contains(ip)) {
						agentIps.add(ip);	
					}
				}
			}
		//	System.err.println("run");
			for(String agentIp : agentIps) {
				sflowCollect(sFlowRTURI, agentIp);
				for(ISflowListener sflowListener : sflowListeners) {
					time=df.format(new Date());
					sflowListener.sflowCollected(ifNameIfStatMap);
				}
			}
		}
	}
}
