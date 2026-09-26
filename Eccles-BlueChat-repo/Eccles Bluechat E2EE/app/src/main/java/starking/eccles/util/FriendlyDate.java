package starking.eccles.util;

import android.util.Base64;
import android.util.Log;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import org.json.JSONObject;

public class FriendlyDate {

	/**
	 * Wire/storage format is unchanged (Base64-encoded JSON with the same field names) so that
	 * existing persisted DATE column values remain readable by {@link #simplify(String)}.
	 */
	public static String format(){
		Date d= new Date();
		String s= "{\"date\":{\"min\":\"" +d.getMinutes()+"\",\"hour\":\""+d.getHours()+"\",\"date\":\""+d.getDate()+"\",\"month\":\""+d.getMonth()+"\",\"year\":\""+d.getYear()+"\""+"}}";
		return Base64.encodeToString(s.getBytes(StandardCharsets.UTF_8),Base64.DEFAULT);
	}

	/**
	 * Converts a timestamp produced by {@link #format()} into a human-friendly relative string
	 * (e.g. "3 minutes ago").
	 * <p>
	 * The previous implementation compared year/month/day/hour/minute fields independently and
	 * returned on the first field where "now" was strictly greater than the stored value. That
	 * produces wildly wrong results whenever the two timestamps straddle a calendar boundary in
	 * a higher field while very little real time has passed &mdash; for example a message sent
	 * at 23:59:00 on Dec 31 read back at 00:01:00 on Jan 1 would report "last year" for
	 * something that happened two minutes ago, because year(now) &gt; year(then) was checked
	 * first regardless of actual elapsed time.
	 * <p>
	 * This version instead computes the actual elapsed {@link Duration} between the two
	 * timestamps and buckets on that, which gives correct results across day/month/year
	 * boundaries.
	 */
	public static String simplify(String d){
		try{
			JSONObject obj= new JSONObject(new String(Base64.decode(d.getBytes(StandardCharsets.UTF_8),Base64.DEFAULT),StandardCharsets.UTF_8)).getJSONObject("date");
			// java.util.Date's deprecated accessors use year-since-1900 and 0-based month;
			// reconstruct real calendar values before building a LocalDateTime.
			int year= obj.getInt("year")+1900;
			int month= obj.getInt("month")+1;
			int day= obj.getInt("date");
			int hour= obj.getInt("hour");
			int minute= obj.getInt("min");
			LocalDateTime stored= LocalDateTime.of(year,month,day,hour,minute);
			return relative(stored,LocalDateTime.now());
		} catch(Exception e){
			Log.e("Eccles","simplify() failed",e);
			return null;
		}
	}

	/** Package-private for testability. */
	static String relative(LocalDateTime stored, LocalDateTime now){
		Duration elapsed= Duration.between(stored,now);
		if(elapsed.isNegative()){
			// Clock skew or a timestamp from the future (e.g. peer device's clock is ahead):
			// don't report a nonsensical negative "ago" value.
			return "just now";
		}
		long seconds= elapsed.getSeconds();
		long minutes= seconds/60;
		long hours= minutes/60;
		long days= hours/24;
		if(minutes<1) return "just now";
		if(hours<1) return minutes==1 ? "a minute ago" : minutes+" minutes ago";
		if(days<1) return hours==1 ? "an hour ago" : hours+" hours ago";
		if(days<30) return days==1 ? "yesterday" : days+" days ago";
		long months= days/30;
		if(months<12) return months==1 ? "last month" : months+" months ago";
		long years= days/365;
		return years==1 ? "last year" : years+" years ago";
	}

	public static String simplify(){
		Date d= new Date();
		return d.getYear()+""+d.getMonth()+""+d.getDate()+""+d.getHours()+""+d.getMinutes()+""+d.getSeconds();
	}

}