package starking.eccles.bluechat.activities;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import starking.eccles.bluechat.EcclesActivity;

public class EcclesOptions extends EcclesActivity {

	@Override
	public boolean onCreateOptionsMenu(Menu m){
		return false;
	}
	@Override
	public boolean onOptionsItemSelected(MenuItem item){
		if(item.getItemId()==android.R.id.home){
			super.onBackPressed();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void onCreate(Bundle b){
		super.onCreate(b);
		getSupportActionBar().setDisplayHomeAsUpEnabled(true);
	}
}