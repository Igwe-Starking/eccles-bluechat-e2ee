package starking.eccles.util;

public class ADPCM {

	private static final int[] INDEX_TABLE= {
		-1,-1,-1,-1,2,4,6,8,
		-1,-1,-1,-1,2,4,6,8
	};

	private static final int[] STEP_TABLE= {
		7,8,9,10,11,12,13,14,16,17,
		19,21,23,25,28,31,34,37,41,45,
		50,55,60,66,73,80,88,97,107,118,
		130,143,157,173,190,209,230,253,279,307,
		337,371,408,449,494,544,598,658,724,796,
		876,963,1060,1166,1282,1411,1552,1707,1878,2066,
		2272,2499,2749,3024,3327,3660,4026,4428,4871,5358,
		5894,6484,7132,7845,8630,9493,10442,11487,12635,13899,
		15289,16818,18500,20350,22385,24623,27086,29794,32767
	};

	public static byte[] encode(byte[] pcm, int len){
		int sampleCount= len/2;
		int predictor= 0;
		int index= 0;

		byte[] out= new byte[3+(sampleCount+1)/2];
		out[0]= (byte)(predictor & 0xFF);
		out[1]= (byte)((predictor>>8) & 0xFF);
		out[2]= (byte) index;

		int outPos= 3;
		boolean high= false;
		int packed= 0;

		for(int i= 0; i<sampleCount; i++){
			int sample= (short)((pcm[i*2] & 0xFF) | (pcm[i*2+1]<<8));

			int step= STEP_TABLE[index];
			int diff= sample-predictor;
			int nibble= 0;

			if(diff<0){
				nibble= 8;
				diff= -diff;
			}

			int tempStep= step;
			if(diff>=tempStep){ nibble |= 4; diff -= tempStep; }
			tempStep >>= 1;
			if(diff>=tempStep){ nibble |= 2; diff -= tempStep; }
			tempStep >>= 1;
			if(diff>=tempStep){ nibble |= 1; }

			int diffq= step >> 3;
			if((nibble & 4) != 0) diffq += step;
			if((nibble & 2) != 0) diffq += step >> 1;
			if((nibble & 1) != 0) diffq += step >> 2;

			if((nibble & 8) != 0) predictor -= diffq; else predictor += diffq;
			if(predictor>32767) predictor= 32767;
			else if(predictor<-32768) predictor= -32768;

			index += INDEX_TABLE[nibble];
			if(index<0) index= 0;
			else if(index>88) index= 88;

			if(!high){
				packed= nibble & 0x0F;
				high= true;
			} else {
				out[outPos++]= (byte)(packed | ((nibble & 0x0F)<<4));
				high= false;
			}
		}
		if(high){
			out[outPos]= (byte) packed;
		}
		return out;
	}

	public static byte[] decode(byte[] data){
		if(data == null || data.length<3) return new byte[0];

		int predictor= (short)((data[0] & 0xFF) | (data[1]<<8));
		int index= data[2] & 0xFF;
		if(index>88) index= 88;

		int nibbleCount= (data.length-3)*2;
		byte[] pcm= new byte[nibbleCount*2];

		int outPos= 0;
		for(int i= 0; i<nibbleCount; i++){
			int b= data[3+(i/2)] & 0xFF;
			int nibble= (i%2==0) ? (b & 0x0F) : ((b>>4) & 0x0F);

			int step= STEP_TABLE[index];
			int diffq= step >> 3;
			if((nibble & 4) != 0) diffq += step;
			if((nibble & 2) != 0) diffq += step >> 1;
			if((nibble & 1) != 0) diffq += step >> 2;

			if((nibble & 8) != 0) predictor -= diffq; else predictor += diffq;
			if(predictor>32767) predictor= 32767;
			else if(predictor<-32768) predictor= -32768;

			index += INDEX_TABLE[nibble];
			if(index<0) index= 0;
			else if(index>88) index= 88;

			pcm[outPos++]= (byte)(predictor & 0xFF);
			pcm[outPos++]= (byte)((predictor>>8) & 0xFF);
		}
		return pcm;
	}
}
