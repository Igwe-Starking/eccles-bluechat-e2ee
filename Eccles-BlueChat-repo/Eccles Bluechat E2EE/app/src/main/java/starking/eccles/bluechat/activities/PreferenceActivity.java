package starking.eccles.bluechat.activities;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;
import androidx.preference.PreferenceDialogFragmentCompat;
import androidx.preference.PreferenceFragmentCompat;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.bclassic.ClassicCompat;
import starking.eccles.bluechat.pref.BlockedPreference;
import starking.eccles.bluechat.pref.EcclesPreference;
import starking.eccles.data.EcclesStorage;

public class PreferenceActivity extends EcclesOptions {

	public static final int RING_CODE= 37;

	@Override
	public void onCreate(Bundle bundle){
		super.onCreate(bundle);
		getSupportActionBar().setTitle("Preference");
		setContentView(R.layout.activity_pref);
		update(new EcclesPreference());
	}

	@Override
	public void onActivityResult(int rc,int res,Intent d){
		super.onActivityResult(rc,res,d);
		if(rc == RING_CODE && res == RESULT_OK){
			if(EcclesStorage.saveTone(this,d.getData())){
				Toast.makeText(this,"Ringtone Changed Successfully",1).show();
			} else {
				Toast.makeText(this,"Failed to update Ringtone",1).show();
			}
		}
	}

	public void update(PreferenceFragmentCompat fragment){
		getSupportFragmentManager().beginTransaction()
		.replace(R.id.con,fragment)
		.commit();
	}

}