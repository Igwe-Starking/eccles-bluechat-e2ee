package starking.eccles.bluechat.ui;

import android.content.Context;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import starking.eccles.bluechat.R;

import androidx.fragment.app.FragmentPagerAdapter;

public class PageAdapter extends FragmentStatePagerAdapter {

	private Context con;
	private final int[] tabs= {R.string.chats,R.string.calls,R.string.videos};

	public PageAdapter(Context con,FragmentManager m){
		super(m);
		this.con= con;

	}
	@Override
	public int getCount() {
	    return 3;
	}

	@Override
	public Fragment getItem(int arg0) {
	    return MainFragment.newInstance(arg0+1);
	}

	public CharSequence getPageTitle(int p){
		return con.getResources().getString(tabs[p]);
	}

}