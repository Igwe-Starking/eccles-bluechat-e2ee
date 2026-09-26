package starking.eccles.bluechat.ui;

import androidx.arch.core.util.Function;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import starking.eccles.Surface.EcclesAdapter;

public class FragmentViewModel extends ViewModel {
	public MutableLiveData<EcclesAdapter> liveData;
	public LiveData<EcclesAdapter> data;

	public FragmentViewModel(){

		liveData= new MutableLiveData();
		data= Transformations.map(liveData,new Function<EcclesAdapter,EcclesAdapter>(){
			public EcclesAdapter apply(EcclesAdapter adapter){
				return adapter;
			}
		});

	}
}