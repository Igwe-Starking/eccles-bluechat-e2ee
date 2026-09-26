package starking.eccles.bluechat;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity  extends Activity {

	private ScheduledExecutorService service;
	private EcclesApplication app;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		app= (EcclesApplication) getApplication();
		if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.LOLLIPOP){
			getWindow().setNavigationBarColor(Color.WHITE);
		}
	}
	@Override
	public void onResume(){
		super.onResume();
		service= Executors.newScheduledThreadPool(1);
		service.scheduleAtFixedRate(()->{
			if(app.databaseOpen){
				runOnUiThread(()->{
					if(service != null)service.shutdown();
					service= null;
					app= null;
					startActivity(new Intent(MainActivity.this,MajorActivity.class));
					finish();
				});
			} else {
				app.openDataBase();
			}
		},500,500,TimeUnit.MILLISECONDS);
	}

	@Override
	public void onPause(){
		super.onPause();
		if(service != null){
			service.shutdown();
			service= null;
		}
	}
}