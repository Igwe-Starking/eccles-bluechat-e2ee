package starking.eccles.Interface;

import androidx.annotation.NonNull;

import java.io.IOException;

import starking.eccles.bluechat.Interface.EcclesReader;
import starking.eccles.bluechat.Interface.EcclesWriter;

public final class EcclesMessage {

	public String sender;
	public transient String date;
	public String to;
	public byte[] data;
	public byte[] voice;
	public byte[] video;
	public int type;
	public int subtype;

	public static final short TYPE_ICON= 3;
	public static final short TYPE_CHAT= 4;
	public static final short TYPE_CALL= 5;
	public static final short TYPE_VIDEO= 6;
	public static final short TYPE_ADDRESS= 13;

	public static final short SUBTYPE_TEXT= 7;
	public static final short SUBTYPE_AUDIO= 8;
	public static final short SUBTYPE_VIDEO= 9;
	public static final short SUBTYPE_CALL_ACCEPTED= 10;
	public static final short SUBTYPE_IMAGE= 11;
	public static final short SUBTYPE_CALL_ONGOING= 12;
	public static final short SUBTYPE_CALL_REJECTED=13;
	public static final short SUBTYPE_CALL_REQUEST=15;
	public static final short SUBTYPE_CALL_RINGING= 16;
	public static final short SUBTYPE_CALL_UNANSWERED= 17;
	public static final short SUBTYPE_CALL_ENDED= 18;
	public static final short SUBTYPE_CALL_CONFIG= 19;

	public EcclesMessage(int type,int subtype){
		this.type= type;
		this.subtype= subtype;
	}

	public EcclesMessage setData(final byte[] data){
		this.data= data;
		return this;
	}

	public EcclesMessage setVoice(byte[] voice){
		this.voice= voice;
		return this;
	}

	public EcclesMessage setVideo(byte[] d){
		this.video= d;
		return this;
	}
	public EcclesMessage setDate(String d){
		this.date= d;
		return this;
	}
	public EcclesMessage setSender(String s){
		this.sender= s;
		return this;
	}

	public boolean send(EcclesWriter stream) throws IOException{
		try{
			synchronized (stream.output) {
				return stream.writeMessage(this);
			}
		}  catch(IOException ie){
			throw ie;
		} catch (Exception e){
			return false;
		}
	}

	public static EcclesMessage read(EcclesReader in) throws IOException,ClassNotFoundException {

		synchronized (in.input){
			return in.readMessage();
		}
	}

	@NonNull
	public EcclesMessage clone(){
		return new EcclesMessage(type,subtype)
				.setSender(sender).setDate(date)
				.setData(data == null ? null : data.clone())
				.setVoice(voice == null ? null : voice.clone())
				.setVideo(video == null ? null : video.clone());
	}
}