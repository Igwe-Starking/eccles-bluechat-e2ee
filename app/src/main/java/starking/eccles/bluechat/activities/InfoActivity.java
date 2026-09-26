package starking.eccles.bluechat.activities;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.view.WindowManager;
import starking.eccles.bluechat.EcclesActivity;
import starking.eccles.bluechat.R;

public class InfoActivity extends EcclesOptions {

	@Override
	public void onCreate(Bundle b){

		setContentView(R.layout.activity_info);
		super.onCreate(b);
		getSupportActionBar().setTitle("Info");
	}

}