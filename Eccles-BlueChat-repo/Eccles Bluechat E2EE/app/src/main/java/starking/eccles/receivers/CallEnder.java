package starking.eccles.receivers;

import android.content.Context;
import android.content.Intent;
import starking.eccles.bluechat.EcclesApplication;
import starking.eccles.bluechat.service.Caller;

public class CallEnder extends EcclesReceiver {

	@Override
	public void onReceive(Context c,Intent i){
		EcclesApplication app= (EcclesApplication) c.getApplicationContext();
		app.stopService(new Intent(app,Caller.class));
	}

}