package starking.eccles.bluechat.activities;

import android.os.Bundle;
import starking.eccles.bluechat.R;
import starking.eccles.bluechat.pref.HelpPref;

public class HelpActivity extends EcclesOptions {

	@Override
	public void onCreate(Bundle b){
		setContentView(R.layout.activity_help);
		super.onCreate(b);
		getSupportActionBar().setTitle("Help");
		getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.con,new HelpPref())
				.commit();
	}
}