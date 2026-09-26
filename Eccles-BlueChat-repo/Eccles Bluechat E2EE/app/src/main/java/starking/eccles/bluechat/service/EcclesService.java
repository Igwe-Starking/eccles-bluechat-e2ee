package starking.eccles.bluechat.service;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;

public class EcclesService extends Service {

	private Channel channel;

	public void onCreate(){
		channel= new Channel();
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId){
		return START_STICKY;
	}

	public IBinder onBind(Intent intent){
		return channel;
	}

	public boolean onUnbind(Intent in){
		return true;
	}

	public class Channel extends Binder {
		public EcclesService getService(){
			return EcclesService.this;
		}
	}
}