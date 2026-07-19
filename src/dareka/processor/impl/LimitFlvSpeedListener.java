package dareka.processor.impl;

import java.io.OutputStream;

import dareka.common.Logger;
import dareka.processor.HttpResponseHeader;
import dareka.processor.Resource;
import dareka.processor.TransferListener;

// 速度自重Listener
public class LimitFlvSpeedListener implements TransferListener
{
	private long transferedBytes = 0;
	private long startedTime = 0;
	private long limitMbps = 1000;
	public LimitFlvSpeedListener() {
		limitMbps = Integer.getInteger("speedLimit", 1000);
	}
	
	public LimitFlvSpeedListener(long mbps) {
		limitMbps = mbps;
	}
	
	// TransferListener interface
	public void onResponseHeader(HttpResponseHeader responseHeader) { }

	public void onTransferBegin(OutputStream receiverOut) {
		startedTime = System.currentTimeMillis();
	}
	
	public void onTransferring(byte[] buf, int length) {
		if (limitMbps < 8) return;
		try {
			transferedBytes += length;
			long elapsedTime = System.currentTimeMillis() - startedTime;
			while (elapsedTime > 0 && transferedBytes / elapsedTime > limitMbps * 1024 / 8) {
				Thread.sleep(15);
				elapsedTime = System.currentTimeMillis() - startedTime;
			}
		} catch (InterruptedException e) {
		}
	}
	
	public void onTransferEnd(boolean completed) {
		//Logger.info("" + (System.currentTimeMillis() - startedTime));
		//Logger.info("kbps: " + 8 * transferedBytes / (System.currentTimeMillis() - startedTime));
	}
	
	public static void addTo(Resource r) {
		int speedLimit = Integer.getInteger("speedLimit", 0);
		if (speedLimit >= 8) {
			Logger.info("transfer speed limit: %,dMbps", speedLimit);
			r.addTransferListener(new LimitFlvSpeedListener(speedLimit));
		}
	}
}