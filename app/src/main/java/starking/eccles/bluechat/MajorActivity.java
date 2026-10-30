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
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.drawerlayout.widget.DrawerLayout;
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
	private View lockOverlay;

	protected void onCreate(Bundle b){

		// Removed the old unconditional-if-true / do-nothing-if-false check here: it only ever
		// forced MODE_NIGHT_YES when the separate "theme" boolean preference was true, and did
		// nothing otherwise - meaning a user who had the system in dark mode but had never
		// touched the in-app switch got dark rendering (AppCompatDelegate's actual implicit
		// default is MODE_NIGHT_FOLLOW_SYSTEM, so that part worked), but the switch itself,
		// reading that same untouched boolean, always showed "off" regardless - the exact
		// "setting says off even though the system is night mode" bug. It's also simply
		// unnecessary: AppCompatDelegate.setDefaultNightMode() persists its own setting
		// internally and reapplies it automatically on every subsequent launch, so there's
		// nothing for this Activity to manually reapply at all - see EcclesPreference's "theme"
		// handling for where the mode is actually set now.
		super.onCreate(b);
		try{
		setContentView(R.layout.activity_main);
		toolbar= findViewById(R.id.toolbar);
		tabs= findViewById(R.id.tabs);
		viewPager= findViewById(R.id.viewpager);
		lockOverlay= findViewById(R.id.lock_overlay);
		lockOverlay.setOnClickListener(v-> checkBiometricLock());

		PageAdapter adapter= new PageAdapter(this,getSupportFragmentManager());
		viewPager.setAdapter(adapter);

		tabs.setupWithViewPager(viewPager);
		tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener(){
			@Override public void onTabSelected(TabLayout.Tab tab){ checkStatus(); }
			@Override public void onTabUnselected(TabLayout.Tab tab){}
			@Override public void onTabReselected(TabLayout.Tab tab){ checkStatus(); }
		});
		toolbar.setTitle(starking.eccles.util.PeerStore.ownName());
		toolbar.setSubtitle("offline");
		toolbar.setSubtitleTextColor(0xFFFFB4AB);
		setSupportActionBar(toolbar);
		getSupportActionBar().setHomeAsUpIndicator(EcclesIcon.resize(50,50,ClassicCompat.queryDeviceIcon("Eccles",this),this));
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);

		handler= new Handler(getMainLooper());

		if(isVisible()){
			SpannableString spa= new SpannableString("available");
			spa.setSpan(new ForegroundColorSpan(0xFFB9D3FF),0,spa.length(),0);
			getSupportActionBar().setSubtitle(spa);
		} else {
			// Requesting discoverability shows a system consent dialog (Android provides no way
			// to do this silently), so the "available" subtitle can only be set once/if the
			// user approves it, not synchronously here.
			requestDiscoverable(300,granted->{
				if(granted){
					SpannableString spa= new SpannableString("available");
					spa.setSpan(new ForegroundColorSpan(0xFFB9D3FF),0,spa.length(),0);
					getSupportActionBar().setSubtitle(spa);
				}
			});
		}

		} catch(Exception e){
			Toast.makeText(this,e.toString(),1).show();
		}

	}

	public void onStart(){

		checkBiometricLock();

		if(app.pref.getBoolean("auto_listen",true)){
			if(listener != null && !listener.listening && !listener.disabled)listener.listen();
		}
		getSupportActionBar().setHomeAsUpIndicator(EcclesIcon.resize(50,50,ClassicCompat.queryDeviceIcon("Eccles",this),this));
		getSupportActionBar().setTitle(starking.eccles.util.PeerStore.ownName());

		super.onStart();
	}

	/**
	 * Prompts for a fingerprint when the "Unlock with Fingerprint" setting is on and the app
	 * needs a fresh check (see {@link EcclesActivity#needsUnlock}'s doc: set every time the
	 * whole app goes to the background - the cold-start case is now handled earlier, before
	 * MajorActivity is even created, by MainActivity's own equivalent check on the splash
	 * screen). MajorActivity specifically (for the background-resume case) because it's what
	 * going back to the app actually resumes - the one place that reliably corresponds to
	 * "entering the app" from the user's perspective, as opposed to every single screen.
	 * <p>
	 * lock_overlay (an opaque view covering the whole activity, added last so it draws on top
	 * of everything) is what actually fixes "the app still loads"/"tap away and go back, it
	 * opens": the old version only ever popped a BiometricPrompt dialog over this Activity's
	 * already-fully-rendered content, so cancelling or dismissing the prompt just closed a
	 * dialog - it didn't un-render the chat list sitting behind it, and however briefly, real
	 * content was visible. The overlay is shown for the *entire* span needsUnlock is true, is
	 * set visible synchronously before the prompt is even requested, and - critically - is NOT
	 * hidden again on cancel/error, only on an actual successful authentication.
	 */
	private static final int BIOMETRIC_AUTHENTICATORS= BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

	/** The lock itself now lives in EcclesActivity (covers the whole window incl. toolbar and
	 *  menu, for every screen). This only re-checks on a tap of the old in-layout overlay. */
	private void checkBiometricLock(){
		if(lockOverlay != null) lockOverlay.setVisibility(View.GONE);
		enforceBiometricLock();
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item){

		if(item.getItemId()== android.R.id.home){
			startActivity(new Intent(this,AccountActivity.class));
			return true;
		}
		switch (item.getTitle().toString()){

			case "Visibility Change":
				// Root cause of it getting stuck offline: this used to branch on isVisible(),
				// which reflects the *actual* Bluetooth adapter scan mode - a value this app
				// cannot reliably turn back off in software (see tryHideFromScans()'s doc: it
				// only works via a reflective call that throws BLUETOOTH_PRIVILEGED
				// SecurityException on virtually every real device, caught there and logged).
				// So once the OS granted discoverability, isVisible() stayed true forever after
				// that - meaning every tap after the first kept re-entering this same "turn it
				// off" branch (already off, nothing to do) and could never reach the
				// requestDiscoverable(...) branch below again. Branching on listener.disabled
				// instead - our own, actually-toggleable app-level "are we listening for
				// connections" state - is what the app can genuinely control, so the toggle now
				// flips both ways.
				boolean currentlyOn= listener == null || !listener.disabled;
				if(currentlyOn){
					if(listener != null){
						 listener.cancelListen();
						 listener.disabled= true;
					}
					if(tryHideFromScans()){
						spanSubTitle("offline",0xFFFFB4AB);
					} else {
						// No public API can revoke discoverability once granted; the most
						// honest thing this app can do is stop actively listening for new
						// connections and tell the user visibility will lapse on its own,
						// rather than claim a "turn off" action succeeded.
						spanSubTitle("offline",0xFFFFB4AB);
						Toast.makeText(this,"Bluechat will stop listening for new connections. Bluetooth visibility will turn off on its own after its timeout.",1).show();
					}
					return false;
				}
				requestDiscoverable(300,granted->{
					if(granted){
						spanSubTitle("available",0xFFB9D3FF);
						if(listener != null){
							listener.disabled= false;
							listener.listen();
						}
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

			spanSubTitle("offline",0xFFFFB4AB);
			sb= showSnack("Bluetooth is off. Basic features will not work.","Enable",0xFF9BF2C4,Snackbar.LENGTH_INDEFINITE,(View) viewPager.getParent(),()->{
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
		// exit() terminates the whole app (app.onTerminate() + finishAffinity). It must only run
		// when this screen is genuinely being closed. A rotation destroys and recreates this
		// Activity too - without the check, every rotation shut the app down and dropped the
		// user to the home screen (with no crash, hence no FATAL EXCEPTION in the log).
		if(isFinishing() && !isChangingConfigurations()){
			exit(false);
		}
		super.onDestroy();
	}
	@Override
	public void onPeerProfileChanged(String address){
		if(fragment != null) fragment.refresh();
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

	// onActive()/onFailed() no longer override anything: EcclesActivity's Listener.ServerStatus
	// implementation moved permanently to EcclesApplication (see the comment above
	// EcclesActivity#onConnected for details). EcclesApplication#onActive/#onFailed now forward
	// to these plain methods whenever this Activity is the currentActivity, so the subtitle
	// still updates. onAccepted() had no such purpose here (it only called the now-removed
	// super.onAccepted()) so it was simply dropped.
	public void onActive(){
		spanSubTitle("active",0xFF9BF2C4);
	}
	public void onFailed(String r){
		spanSubTitle("offline",0xFFFFB4AB);
	}

	/** Recomputes the three tab badges from stored state. Chats = total unread messages across
	 *  all chats (drops as each chat is opened/read). Calls/Video = missed calls only, and they
	 *  are cleared for good (stored count reset, not just the icon hidden) as soon as that tab
	 *  is the one on screen, since seeing the tab means the misses have been seen. */
	public void checkStatus(){
		if(tabs == null) return;
		int sel= tabs.getSelectedTabPosition();
		if(sel == 1) ClassicCompat.clearMiss(this,false);
		if(sel == 2) ClassicCompat.clearMiss(this,true);

		setBadge(0,ClassicCompat.totalUnRead(this),0xFF00BFA5,Color.WHITE);
		setBadge(1,ClassicCompat.getMiss(this,false),0xFFE5484D,Color.WHITE);
		setBadge(2,ClassicCompat.getMiss(this,true),0xFFE5484D,Color.WHITE);
	}

	private void setBadge(int tab,int count,int bg,int fg){
		TabLayout.Tab t= tabs.getTabAt(tab);
		if(t == null) return;
		if(count > 0) t.setIcon(ClassicCompat.createUnreadBit(this,String.valueOf(count),bg,fg));
		else t.setIcon(null);
	}

	public void clearStatus(boolean video){
		ClassicCompat.clearMiss(this,video);
		checkStatus();
	}
}