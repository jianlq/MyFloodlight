package net.floodlightcontroller.sflowcollector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
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

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.internal.IOFSwitchService;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.FloodlightModuleLoader;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
//import net.floodlightcontroller.output.*;

import org.json.JSONException;
import org.json.JSONObject;
import org.restlet.ext.json.JsonRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class SflowCollector implements IFloodlightModule, ISflowCollectionService {
	public static final String sflowRtUriPropStr = "net.floodlightcontroller.sflowcollector.SflowCollector.uri";
	
	public static final long DEFAULT_FIRST_DELAY = 10000L;
	public static final long DEFAULT_PERIOD = 2000L;
	protected IOFSwitchService switchService;
	protected Map<Integer, InterfaceStatistics> ifIndexIfStatMap;
	protected Set<ISflowListener> sflowListeners;
	protected String sFlowRTURI;
	protected long firstDelay;
	protected long period;
	protected static Logger log;
	List<String> agentIps;
	//protected Output sflowCollectTxt;
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
		//l.add(IIfIndexCollectionService.class);
		return l;
	}

	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException {
	    switchService = context.getServiceImpl(IOFSwitchService.class);
		sflowListeners = new CopyOnWriteArraySet<ISflowListener>(); // 参考http://ifeve.com/tag/copyonwritearrayset/
		
		log = LoggerFactory.getLogger(SflowCollector.class);
		ifIndexIfStatMap = new ConcurrentHashMap<Integer, InterfaceStatistics>();  // 参考  http://www.iteye.com/topic/1103980
		
		
		log.info("---------------------Sflow Collector init "); //jian
		
		// 上述两个容器都是为了并发操作的线程安全
		
		//sflowCollectTxt=new Output("sflowCollectTxt.txt");
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
			System.err.println("SflowCollector Not Enabled.");
			return;
		}
		sFlowRTURI = prop.getProperty(sflowRtUriPropStr);
		if(sFlowRTURI == null || sFlowRTURI.length() == 0) {
			System.err.println("Could not load sFlow-RT URI configuration file");
			System.exit(1);
		}
		firstDelay = DEFAULT_FIRST_DELAY;
		period = DEFAULT_PERIOD;
		
		log.info("---------------------Sflow Collector starup "); //jian
		
		agentIps = new ArrayList<String>();
		readIpFile("inputFile/agent_ip.txt",agentIps);
		System.err.println("start up ip");
		for(String agentIp : agentIps) {
			System.err.println(agentIp);
		}
		
		Timer t = new Timer("SflowCollectionTimer");
		t.schedule(new SflowCollectTimerTask(), firstDelay, period);
		
		
	}
	
	private void sflowCollect(String baseUri, String agentIp) {
		
		SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		String time=df.format(new Date());
		String uri = baseUri.replace("{agent_ip}", agentIp);		
		ClientResource resource = new ClientResource(uri);
	
		System.err.println("-------- uri " + uri);
		
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
			System.err.println("Get JSON failed.");
		}
		
		@SuppressWarnings("unchecked")
		Iterator<String> it = jo.keys();
		while(it.hasNext()) {
			String key = it.next(); 
			String statProp = key.substring(key.indexOf(".") + 1);
			if(InterfaceStatistics.ALLJSONPROPERTIES.contains(statProp)) {
				Integer ifIndex = -1;
				try {
					ifIndex = Integer.parseInt(key.substring(0, key.indexOf(".")));
					
				} catch(NumberFormatException e) {
					continue;
				}
				if (ifIndex >= 0) {
					//System.err.println(" -----ifIndex"+ifIndex);

					String ifName = null;

					if (!ifIndexIfStatMap.containsKey(ifIndex)) {
						ifIndexIfStatMap.put(ifIndex, new InterfaceStatistics(
								ifIndex, ifName));
					}
				} else {
					System.err.println("------cannot get ifName from sflowdata");
				}
				InterfaceStatistics is = ifIndexIfStatMap.get(ifIndex);
				is.fromJsonProp(key, jo);

			}
		}
		
		// output
		for(InterfaceStatistics is:ifIndexIfStatMap.values()){
			System.err.println(is.getIfIndex()+is.toString());
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
	
	// 读入sflow agent ip 文件
	public void readIpFile(String fileName, List<String> agentIps) {
		File file = new File(fileName);
		BufferedReader reader = null;
		if (file.isFile() && file.exists()) {
			try {
				reader = new BufferedReader(new FileReader(file));
				String ip = null;
				while ((ip = reader.readLine()) != null) {
					agentIps.add(ip);
				}
				reader.close();
			} catch (IOException e) {
				System.err.println("File Error!");
			}finally {
	            if (reader != null) {
	                try {
	                    reader.close();
	                } catch (IOException e1) {
	                }
	            }
		   }
		}else {
			System.err.println("can not find flie agent_ip.txt ");
		}
	}
	
	private class SflowCollectTimerTask extends TimerTask {

		@Override
		public void run() {	
			SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
			String time=df.format(new Date());
			//sflowCollectTxt.writeData(time);
			
		
			// 手动加ip					
			/*String ip = "10.0.0.100";
			agentIps.add(ip);
			agentIps.add("10.0.0.101");
			System.err.println("----- ip");
			for(String agentIp : agentIps) {
				System.err.println(agentIp);
			}*/
						
			/*System.err.println("file ip");
			for(String agentIp : agentIps) {
				System.err.println(agentIp);
			}*/
			
		    System.err.println("-------CollectTask run");
			for(String agentIp : agentIps) {
				//System.err.println("test:"+agentIp);
				sflowCollect(sFlowRTURI, agentIp);
				//交换机端口映射
				
				for(ISflowListener sflowListener : sflowListeners) {
					time=df.format(new Date());
					sflowListener.sflowCollected(ifIndexIfStatMap);
				}
			}
		}
	}
}
