package starking.eccles.bluechat;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.preference.PreferenceManager;
import androidx.viewpager.widget.ViewPager;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import starking.eccles.Surface.EcclesAdapter;
import starking.eccles.Surface.EcclesPojo;
import starking.eccles.bluechat.activities.AccountActivity;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.service.EcclesService;
import starking.eccles.bluechat.service.Listener;
import starking.eccles.bluechat.ui.MainFragment;
import starking.eccles.bluechat.ui.PageAdapter;
import starking.eccles.util.EcclesIcon;

public class MajorActivity extends EcclesActivity {

	Toolbar toolbar;
	TabLayout tabs;
	ViewPager viewPager;
	private Handler handler;
	private ServiceConnection connection;
	public MainFragment fragment;
	private Snackbar sb;

	protected void onCreate(Bundle b){

		if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("theme",false)){
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
		}
		super.onCreate(b);
		try{
		setContentView(R.layout.activity_main);
		toolbar= findViewById(R.id.toolbar);
		tabs= findViewById(R.id.tabs);
		viewPager= findViewById(R.id.viewpager);

		PageAdapter adapter= new PageAdapter(this,getSupportFragmentManager());
		viewPager.setAdapter(adapter);

		tabs.setupWithViewPager(viewPager);
		toolbar.setTitle(((EcclesApplication)getApplication()).name);
		toolbar.setSubtitle("offline");
		toolbar.setSubtitleTextColor(Color.RED);
		setSupportActionBar(toolbar);
		getSupportActionBar().setHomeAsUpIndicator(EcclesIcon.resize(50,50,ClassicCompat.queryDeviceIcon("Eccles",this),this));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		handler= new Handler(getMainLooper());

		if(isVisible()){
			SpannableString spa= new SpannableString("available");
			spa.setSpan(new ForegroundColorSpan(Color.BLUE),0,spa.length(),0);
			getSupportActionBar().setSubtitle(spa);
		} else {
			// Requesting discoverability shows a system consent dialog (Android provides no way
			// to do this silently), so the "available" subtitle can only be set once/if the
			// user approves it, not synchronously here.
			requestDiscoverable(300,granted->{
				if(granted){
					SpannableString spa= new SpannableString("available");
					spa.setSpan(new ForegroundColorSpan(Color.BLUE),0,spa.length(),0);
					getSupportActionBar().setSubtitle(spa);
				}
			});
		}

		} catch(Exception e){
			Toast.makeText(this,e.toString(),1).show();
		}

	}

	public void onStart(){

		if(app.pref.getBoolean("auto_listen",true)){
			if(listener != null && !listener.listening && !listener.disabled)listener.listen();
		}
		getSupportActionBar().setHomeAsUpIndicator(EcclesIcon.resize(50,50,ClassicCompat.queryDeviceIcon("Eccles",this),this));
		getSupportActionBar().setTitle(app.adapter.getName());

		super.onStart();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){

		if(item.getItemId()== android.R.id.home){
			startActivity(new Intent(this,AccountActivity.class));
			return true;
		}
		switch (item.getTitle().toString()){

			case "Visibility Change":
				if(isVisible()){
					if(listener != null){
						 listener.cancelListen();
						 listener.disabled= true;
					}
					if(tryHideFromScans()){
						spanSubTitle("offline",Color.RED);
					} else {
						// No public API can revoke discoverability once granted; the most
						// honest thing this app can do is stop actively listening for new
						// connections and tell the user visibility will lapse on its own,
						// rather than claim a "turn off" action succeeded.
						spanSubTitle("offline",Color.RED);
						Toast.makeText(this,"Bluechat will stop listening for new connections. Bluetooth visibility will turn off on its own after its timeout.",1).show();
					}
					return false;
				}
				requestDiscoverable(300,granted->{
					if(granted){
						spanSubTitle("available",Color.BLUE);
						if(listener != null) listener.listen();
					}
				});
				break;
		}
			return super.onOptionsItemSelected(item);
	}

	public void spanSubTitle(String s,int c){

		handler.post(()->{
			SpannableString sp= new SpannableString(s);
			sp.setSpan(new ForegroundColorSpan(c),0,sp.length(),0);
			getSupportActionBar().setSubtitle(sp);
		});

	}

	@Override
	public void state(boolean on){

		if(!on){

			spanSubTitle("offline",Color.RED);
			sb= showSnack("Bluetooth is off Basic functionalities will not work","Enable",Color.GREEN,Snackbar.LENGTH_INDEFINITE,(View) viewPager.getParent(),()->{
				if(app.adapter.enable()){
					app.turnedOn= true;
				}
			});
			} else {
				if(listener != null && !listener.disabled && app.pref.getBoolean("auto_listen",true)){
					listen();
				}
				if(sb != null) sb.dismiss();

		}
		super.state(on);
	}

	public void onBackPressed(){
		exit(true);
	}

	@Override
	public void onDestroy(){
		exit(false);
		super.onDestroy();
	}
	@Override
	public void onConnected(BluetoothSocket s){
		fragment.onConnectionChanged(s.getRemoteDevice(),true);
		super.onConnected(s);
	}
	@Override
	public void onDisconnected(BluetoothDevice d){
		if(fragment != null)fragment.onConnectionChanged(d,false);
		super.onDisconnected(d);
	}
	@Override
	public void onResume(){
		// setStatusListener(this) used to be called here too - see EcclesActivity's onCreate()
		// for why this per-Activity re-registration was removed entirely.
		if(listener != null && !listener.listening && !listener.disabled && app.pref.getBoolean("auto_listen",true)){
			listen();
		}
		checkStatus();

		super.onResume();
	}

	@Override
	public void onActive(){
		spanSubTitle("active",Color.GREEN);
	}
	@Override
	public void onAccepted(BluetoothSocket socket){
		super.onAccepted(socket);
	}
	@Override
	public void onFailed(String r){
		spanSubTitle("offline",Color.RED);
		super.onFailed(r);
	}

	public void checkStatus(){
		int i= ClassicCompat.totalUnRead(this);
		if(i>0){
			tabs.getTabAt(0).setIcon(ClassicCompat.createUnreadBit(this,String.valueOf(i),Color.WHITE,Color.GREEN));
		} else {
			tabs.getTabAt(0).setIcon(null);
		}

		int ii= ClassicCompat.getMiss(this,false);
		if(ii>0){
			tabs.getTabAt(1).setIcon(ClassicCompat.createUnreadBit(this,String.valueOf(ii),Color.RED,Color.WHITE));
		}

		int iii= ClassicCompat.getMiss(this,true);
		if(iii>0){
			tabs.getTabAt(2).setIcon(ClassicCompat.createUnreadBit(this,String.valueOf(iii),Color.RED,Color.YELLOW));
		}
	}

	public void clearStatus(boolean video){
		if(video){
			tabs.getTabAt(2).setIcon(null);
			return;
		}
		tabs.getTabAt(1).setIcon(null);
	}
}