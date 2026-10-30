package starking.eccles.bluechat;

import android.content.Intent;
import android.graphics.Color;

import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * This is the app's actual launcher Activity - the "Splash" theme (a plain background image,
 * see AndroidManifest.xml/themes.xml) is what the person sees while this waits for the database
 * to open, before handing off to MajorActivity.
 * <p>
 * It's also now where the "Unlock with Fingerprint" check happens on a cold start, instead of
 * inside MajorActivity's own onStart() (which still separately re-checks on every
 * background-to-foreground resume, since this Activity is a one-time launcher and isn't shown
 * again for that case - see its own checkBiometricLock doc). Gating entry *here*, before
 * MajorActivity is ever created, is what "hang until password/fingerprint is correctly entered"
 * actually requires: MajorActivity's real content (the chat list, etc.) simply doesn't exist
 * yet at this point, so there's nothing for a successful or failed check to accidentally expose
 * - unlike checking from inside MajorActivity itself, which can only ever hide already-created
 * content behind an overlay after the fact.
 * <p>
 * Changed from plain Activity to FragmentActivity: BiometricPrompt needs a FragmentManager to
 * host its internal dialog, which plain Activity doesn't have.
 */
public class MainActivity extends FragmentActivity {

	private ScheduledExecutorService service;
	private EcclesApplication app;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		app= (EcclesApplication) getApplication();
		if(app.pref.getBoolean("biometric_lock",false)){
			getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,android.view.WindowManager.LayoutParams.FLAG_SECURE);
		}
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
					proceedPastSplash();
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

	private void proceedPastSplash(){
		if(EcclesActivity.needsUnlock && app.pref.getBoolean("biometric_lock",false)){
			checkBiometricLock();
		} else {
			launchMajorActivity();
		}
	}

	private void launchMajorActivity(){
		app= null;
		startActivity(new Intent(MainActivity.this,MajorActivity.class));
		finish();
	}

	private static final int BIOMETRIC_AUTHENTICATORS= BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL;

	private void checkBiometricLock(){
		if(BiometricManager.from(this).canAuthenticate(BIOMETRIC_AUTHENTICATORS) != BiometricManager.BIOMETRIC_SUCCESS){
			// Neither a fingerprint NOR any device PIN/pattern/password is set up at all - there
			// is genuinely nothing to authenticate against. Rather than permanently locking the
			// user out of their own app over a setting they currently have no way to satisfy,
			// send them to set up a screen lock and let them in this once.
			Toast.makeText(this,"Set up a fingerprint or screen lock to use this setting",Toast.LENGTH_LONG).show();
			startActivity(new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS));
			EcclesActivity.needsUnlock= false;
			launchMajorActivity();
			return;
		}

		BiometricPrompt.PromptInfo info= new BiometricPrompt.PromptInfo.Builder()
				.setTitle("Unlock Bluechat")
				.setSubtitle("Confirm your fingerprint or device PIN/pattern/password to continue")
				.setAllowedAuthenticators(BIOMETRIC_AUTHENTICATORS)
				.build();

		BiometricPrompt prompt= new BiometricPrompt(this,ContextCompat.getMainExecutor(this),
				new BiometricPrompt.AuthenticationCallback(){
					@Override
					public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result){
						super.onAuthenticationSucceeded(result);
						EcclesActivity.needsUnlock= false;
						launchMajorActivity();
					}
					@Override
					public void onAuthenticationError(int errorCode,CharSequence errString){
						super.onAuthenticationError(errorCode,errString);
						// Cancelled, or failed past the retry limit - this is the app's actual
						// entry gate, so there's no "previous screen" to fall back to; close the
						// app outright rather than proceeding into MajorActivity unauthenticated.
						// Relaunching from the icon shows this same splash+prompt again.
						finish();
					}
				});
		prompt.authenticate(info);
	}
}
