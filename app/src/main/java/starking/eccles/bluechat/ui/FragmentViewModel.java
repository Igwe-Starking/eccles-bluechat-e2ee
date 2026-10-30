package starking.eccles.bluechat.ui;

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
		// Transformations.map() now takes a Kotlin Function1<X,Y> (from lifecycle-livedata-core-ktx)
		// instead of the old androidx.arch.core.util.Function. Function1 is still a single-abstract-
		// method interface under the hood, so a plain Java lambda satisfies it via SAM conversion.
		data= Transformations.map(liveData, adapter -> adapter);

	}
}