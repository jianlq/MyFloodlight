package net.floodlightcontroller.statistics;

import java.math.BigInteger;
import java.util.Date;

import org.projectfloodlight.openflow.types.DatapathId;
import org.projectfloodlight.openflow.types.OFPort;
import org.projectfloodlight.openflow.types.U64;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import net.floodlightcontroller.statistics.web.SwitchPortBandwidthSerializer;

@JsonSerialize(using=SwitchPortBandwidthSerializer.class)
public class SwitchPortBandwidth {
	private DatapathId id;
	private OFPort pt;
	private U64 rx; //
	private U64 tx;
	private Date time;
	private U64 rxValue; //字节数
	private U64 txValue;
	
	private SwitchPortBandwidth() {}
	private SwitchPortBandwidth(DatapathId d, OFPort p, U64 rx, U64 tx, U64 rxValue, U64 txValue) {
		id = d;
		pt = p;
		this.rx = rx;
		this.tx = tx;
		time = new Date();
		this.rxValue = rxValue;
		this.txValue = txValue;
	}
	
	public static SwitchPortBandwidth of(DatapathId d, OFPort p, U64 rx, U64 tx, U64 rxValue, U64 txValue) {
		if (d == null) {
			throw new IllegalArgumentException("Datapath ID cannot be null");
		}
		if (p == null) {
			throw new IllegalArgumentException("Port cannot be null");
		}
		if (rx == null) {
			throw new IllegalArgumentException("RX bandwidth cannot be null");
		}
		if (tx == null) {
			throw new IllegalArgumentException("TX bandwidth cannot be null");
		}
		if (rxValue == null) {
			throw new IllegalArgumentException("RX value cannot be null");
		}
		if (txValue == null) {
			throw new IllegalArgumentException("TX value cannot be null");
		}
		return new SwitchPortBandwidth(d, p, rx, tx, rxValue, txValue);
	}
	
	// jian 2016 - 5 -13 以下函数自定义
	/*public int getBandwidth(){
		int bw ;
		bw = txValue.getBigInteger().intValue() -rx.getBigInteger().intValue();
		//time.getSeconds()
		return bw;
	}*/
	
	public DatapathId getSwitchId() {
		return id;
	}
	
	public OFPort getSwitchPort() {
		return pt;
	}
	
	public U64 getBitsPerSecondRx() {
		return rx;
	}
	
	public U64 getBitsPerSecondTx() {
		return tx;
	}
	
	public U64 getPriorByteValueRx() {   // jian 将 protected 改 为 public  为了forwarding中使用
		return rxValue;
	}
	
	public U64 getPriorByteValueTx() {  // // 将 protected 改 为 public  为了forwarding中使用
		return txValue;
	}
	
	public long getUpdateTime() {
		return time.getTime();
	}
		
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((id == null) ? 0 : id.hashCode());
		result = prime * result + ((pt == null) ? 0 : pt.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		SwitchPortBandwidth other = (SwitchPortBandwidth) obj;
		if (id == null) {
			if (other.id != null)
				return false;
		} else if (!id.equals(other.id))
			return false;
		if (pt == null) {
			if (other.pt != null)
				return false;
		} else if (!pt.equals(other.pt))
			return false;
		return true;
	}
}