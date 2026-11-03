package ix7;
public class TapTest {
  static int crc(int[] b){int c=0xFFFF;for(int x:b){c^=x;for(int k=0;k<8;k++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}return c;}
  static byte[] fr(int cmd,int tipo,int... data){int n=data.length;byte[] f=new byte[9+n];f[0]=0x1A;f[1]=(byte)0xA1;f[2]=(byte)cmd;f[3]=(byte)tipo;f[4]=(byte)n;
    int[] c=new int[3+n];c[0]=cmd;c[1]=tipo;c[2]=n;for(int i=0;i<n;i++){f[5+i]=(byte)data[i];c[3+i]=data[i];}int r=crc(c);f[5+n]=(byte)(r&0xff);f[6+n]=(byte)(r>>8);f[7+n]=0x1F;f[8+n]=(byte)0xF1;return f;}
  static void check(boolean ok,String m){ if(!ok){System.out.println("FAIL "+m);System.exit(1);} System.out.println("ok "+m);}
  static String d(byte[] b){ return Tap.describe(b,true); }
  static String r(byte[] b){ return Tap.describe(b,false); }
  public static void main(String[] a){
    String s=d(fr(0x32,2,0,1,2,3,4));
    check(s.startsWith("1aa1320205")&&s.contains("00********")&&s.contains("crc=lohi")&&s.contains("lock_data_len=5"),"lock pin+crc masked: "+s);
    check(!s.contains("01020304"),"pin digits absent");
    String q=d(fr(0x67,1,9,8,7,6));
    check(q.contains("1aa1670104************")&&q.endsWith("1ff1 crc=lohi"),"0x67 masked: "+q);
    String m=d(fr(0x20,1,0,0x30,0,80,1,2,3,4,5,6,7,8));
    check(m.contains("crc=lohi")&&!m.contains("**"),"meter1 clear: "+m);
    check(r(fr(0x20,3,0,0x30,0,80)).endsWith("crc=modbus_lohi"),"RX modbus named: "+r(fr(0x20,3,0,0x30,0,80)));
    byte[] two=new byte[18]; byte[] h=fr(0x10,2); System.arraycopy(h,0,two,0,9); System.arraycopy(h,0,two,9,9);
    check(d(two).split("\\|").length==2,"two frames: "+d(two));
    check(d(new byte[]{0x1A,(byte)0xA1,0x67,1,4,9,9}).equals("nonframe_len=7"),"partial frame only length");
    byte[] bad=fr(0x30,2,1); bad[6]^=1; check(d(bad).startsWith("badcrc cmd=30"),"TX bad crc: header only: "+d(bad));
    check(r(bad).startsWith("badcrc cmd=30"),"RX bad crc: header only: "+r(bad));
    // Codex R1-r5: 0x61 LEN=10 with CMD corrupted to 0x60, and 0x3A->0x3C
    byte[] sn=fr(0x61,1,1,2,3,4,5,6,7,8,9,0); sn[2]=0x60; check(r(sn).startsWith("badcrc"),"RX 0x61->0x60 hidden: "+r(sn));
    byte[] ss=fr(0x3A,2,1,2,3,4,5,6); ss[2]=0x3C; check(r(ss).startsWith("badcrc"),"RX 0x3A->0x3C hidden: "+r(ss));
    // Codex R1-r5: heartbeat + LOCK swallowed, inner CMD 32->30
    byte[] hb2=fr(0x10,2); byte[] lk2=fr(0x32,2,0,1,2,3,4); lk2[2]=0x30; byte[] cat2=new byte[hb2.length+lk2.length];
    System.arraycopy(hb2,0,cat2,0,hb2.length); System.arraycopy(lk2,0,cat2,hb2.length,lk2.length); cat2[4]=(byte)lk2.length;
    check(!r(cat2).contains("01020304")&&!d(cat2).contains("01020304"),"swallowed lock with corrupted inner cmd hidden: "+r(cat2));
    // RX frame valid under an alternative candidate (CCITT-FALSE) is printed with its name
    byte[] cc=fr(0x20,3,0,0x30,0,80); int x=0xFFFF; for(int k=2;k<9;k++){x^=(cc[k]&0xff)<<8;for(int b=0;b<8;b++){x=(x&0x8000)!=0?((x<<1)^0x1021):(x<<1);x&=0xffff;}}
    cc[9]=(byte)(x&0xff); cc[10]=(byte)(x>>8); check(r(cc).endsWith("crc=ccitt_lohi")&&d(cc).startsWith("badcrc"),"RX ccitt accepted, TX not: "+r(cc));
    // Codex R1-r4: LOCK with CMD corrupted 32->30, CRC not recomputed
    byte[] cor=fr(0x32,2,0,1,2,3,4); cor[2]=0x30; String c1=d(cor), c2=r(cor);
    check(!c1.contains("01020304")&&!c2.contains("01020304")&&c2.startsWith("badcrc"),"corrupted lock cmd hidden: "+c1+" / "+c2);
    byte[] sec=fr(0x67,1,9,8,7,6); sec[2]=0x20; check(r(sec).startsWith("badcrc"),"RX corrupted 0x67->0x20 len4 hidden: "+r(sec));
    byte[] badlock=fr(0x32,5,1,1,2,3,4); badlock[6]^=0x40; String bl=r(badlock);
    check(bl.startsWith("badcrc cmd=32")&&!bl.contains("01020304"),"RX bad-crc lock header only: "+bl);
    check(d(null).equals("null"),"null");
    // Codex R1 P1: LEN of a heartbeat forged to swallow a LOCK frame
    byte[] hb=fr(0x10,2); byte[] lk=fr(0x32,2,1,1,2,3,4); byte[] cat=new byte[hb.length+lk.length];
    System.arraycopy(hb,0,cat,0,hb.length); System.arraycopy(lk,0,cat,hb.length,lk.length); cat[4]=(byte)(lk.length);
    String sw=d(cat);
    check((sw.startsWith("embedded")||sw.startsWith("badcrc"))&&!sw.contains("01020304"),"swallowed lock not printed (TX): "+sw);
    String swr=r(cat); check((swr.startsWith("embedded")||swr.startsWith("badcrc"))&&!swr.contains("01020304"),"swallowed lock not printed (RX): "+swr);
    // valid-CRC outer frame that embeds a LOCK start in its payload
    int[] inner=new int[lk.length]; for(int i=0;i<lk.length;i++) inner[i]=lk[i]&0xff;
    String em=d(fr(0x10,2,inner));
    check(em.startsWith("embedded")&&!em.contains("01020304"),"embedded lock masked: "+em);
  }
}
