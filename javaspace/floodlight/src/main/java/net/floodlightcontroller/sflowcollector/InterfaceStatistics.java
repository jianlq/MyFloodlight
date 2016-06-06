package net.floodlightcontroller.sflowcollector;

import java.text.SimpleDateFormat;
import java.util.Date;
import org.apache.commons.lang.time.DateFormatUtils;
import org.json.JSONException;
import org.json.JSONObject;


public class InterfaceStatistics {
	public static final String ALLJSONPROPERTIES = "ifInmulticastpkts ifindex ifindiscards ifinerrors ifinoctets ifinucastpkts ifoutdiscards ifouterrors ifoutoctets ifoutucastpkts ifspeed iftype";
	
	private Integer ifIndex;
	private String ifName;
	private String ifType;
	private Double ifSpeed;
	private Double ifInMulticastpkts;
	private Double ifInUcastpkts;
	private Double ifInOctets; //接收速率
	private Double ifInDiscards;
	private Double ifInErrors;
	private Double ifOutUcastpkts;
	private Double ifOutOctets; // 发送速率
	private Double ifOutDiscards;
	private Double ifOutErrors;
	private String time;
	public InterfaceStatistics() { }
	
	public InterfaceStatistics(Integer ifIndex, String ifName) {
		this.ifIndex = ifIndex;
		this.ifName = ifName;
		SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		this.time=df.format(new Date());
	}	
	public InterfaceStatistics(Integer ifIndex, String ifName, String ifType,
			Double ifSpeed, Double ifInMulticastpkts, Double ifInUcastpkts,
			Double ifInOctets, Double ifInDiscards, Double ifInErrors,
			Double ifOutUcastpkts, Double ifOutOctets, Double ifOutDiscards,
			Double ifOutErrors) {
		super();
		this.ifIndex = ifIndex;
		this.ifName = ifName;
		this.ifType = ifType;
		this.ifSpeed = ifSpeed;
		this.ifInMulticastpkts = ifInMulticastpkts;
		this.ifInUcastpkts = ifInUcastpkts;
		this.ifInOctets = ifInOctets;
		this.ifInDiscards = ifInDiscards;
		this.ifInErrors = ifInErrors;
		this.ifOutUcastpkts = ifOutUcastpkts;
		this.ifOutOctets = ifOutOctets;
		this.ifOutDiscards = ifOutDiscards;
		this.ifOutErrors = ifOutErrors;
		SimpleDateFormat df=new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
		this.time=df.format(new Date());
	}
	
	public void fromJsonProp(String jsonProp, JSONObject jo) {
		if(jsonProp == null || jsonProp.length() == 0) return;
		if(jo == null || !jo.has(jsonProp)) return;
		time=DateFormatUtils.format(new Date(),
				"yyyy/MM/dd/ HH:mm:ss");	
		try {
			if (jsonProp.contains("iftype")) {
				ifType = jo.getString(jsonProp);
			} else if (jsonProp.contains("ifspeed")) {
				ifSpeed = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifInmulticastpkts")) {
				ifInMulticastpkts = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifinucastpkts")) {
				ifInUcastpkts = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifinoctets")) {
				ifInOctets = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifindiscards")) {
				ifInDiscards = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifinerrors")) {
				ifInErrors = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifoutucastpkts")) {
				ifOutUcastpkts = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifoutoctets")) {
				ifOutOctets = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifoutdiscards")) {
				ifOutDiscards = jo.getDouble(jsonProp);
			} else if (jsonProp.contains("ifouterrors")) {
				ifOutErrors = jo.getDouble(jsonProp);
			}
			
			
		} catch (JSONException e) {
			return;
		}
	}
	
	public String toString() {
		StringBuffer sb = new StringBuffer();
		sb.append("[ifIndex=" + ifIndex);
		sb.append(", ifName=" + ifName);
		sb.append(", ifType=" + ifType);
		sb.append(", ifSpeed=" + ifSpeed);
		sb.append(", ifInMulticastpkts=" + ifInMulticastpkts);
		sb.append(", ifInUcastpkts=" + ifInUcastpkts);
		sb.append(", ifInOctets=" + ifInOctets);
		sb.append(", ifInDiscards=" + ifInDiscards);
		sb.append(", ifInErrors=" + ifInErrors);
		sb.append(", ifOutUcastpkts=" + ifOutUcastpkts);
		sb.append(", ifOutOctets=" + ifOutOctets);
		sb.append(", ifOutDiscards=" + ifOutDiscards);
		sb.append(", ifOutErrors=" + ifOutErrors + "]");
		return sb.toString();
	}

	public Integer getIfIndex() {
		return ifIndex;
	}
	public String getIfName() {
		return ifName;
	}
	public String getIfType() {
		return ifType;
	}
	public Double getIfSpeed() {
		return ifSpeed;
	}
	public Double getIfInMulticastpkts() {
		return ifInMulticastpkts;
	}
	public Double getIfInUcastpkts() {
		return ifInUcastpkts;
	}
	public Double getIfInOctets() {
		return ifInOctets;
	}
	public Double getIfInDiscards() {
		return ifInDiscards;
	}
	public Double getIfInErrors() {
		return ifInErrors;
	}
	public Double getIfOutUcastpkts() {
		return ifOutUcastpkts;
	}
	public Double getIfOutOctets() {
		return ifOutOctets;
	}
	public Double getIfOutDiscards() {
		return ifOutDiscards;
	}
	public Double getIfOutErrors() {
		return ifOutErrors;
	}
	public String getTime(){
		return time;
	}
	public void setIfIndex(Integer ifIndex) {
		this.ifIndex = ifIndex;
	}
	public void setIfName(String ifName) {
		this.ifName = ifName;
	}
	public void setIfType(String ifType) {
		this.ifType = ifType;
	}
	public void setIfSpeed(Double ifSpeed) {
		this.ifSpeed = ifSpeed;
	}
	public void setIfInMulticastpkts(Double ifInMulticastpkts) {
		this.ifInMulticastpkts = ifInMulticastpkts;
	}
	public void setIfInUcastpkts(Double ifInUcastpkts) {
		this.ifInUcastpkts = ifInUcastpkts;
	}
	public void setIfInOctets(Double ifInOctets) {
		this.ifInOctets = ifInOctets;
	}
	public void setIfInDiscards(Double ifInDiscards) {
		this.ifInDiscards = ifInDiscards;
	}
	public void setIfInErrors(Double ifInErrors) {
		this.ifInErrors = ifInErrors;
	}
	public void setIfOutUcastpkts(Double ifOutUcastpkts) {
		this.ifOutUcastpkts = ifOutUcastpkts;
	}
	public void setIfOutOctets(Double ifOutOctets) {
		this.ifOutOctets = ifOutOctets;
	}
	public void setIfOutDiscards(Double ifOutDiscards) {
		this.ifOutDiscards = ifOutDiscards;
	}
	public void setIfOutErrors(Double ifOutErrors) {
		this.ifOutErrors = ifOutErrors;
	}
	public void setTime(String time){
		this.time=time;
	}
}
