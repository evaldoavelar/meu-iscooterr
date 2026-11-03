package ix7;
public class TapTest {
  static int crc(int[] b){int c=0xFFFF;for(int x:b){c^=x;for(int k=0;k<8;k++)c=(c&1)!=0?(c>>>1)^0xA001:c>>>1;}return c;}
  static byte[] fr(int cmd,int tipo,int... data){int n=data.length;byte[] f=new byte[9+n];f[0]=0x1A;f[1]=(byte)0xA1;f[2]=(byte)cmd;f[3]=(byte)tipo;f[4]=(byte)n;
    int[] c=new int[3+n];c[0]=cmd;c[1]=tipo;c[2]=n;for(int i=0;i<n;i++){f[5+i]=(byte)data[i];c[3+i]=data[i];}int r=crc(c);f[5+n]=(byte)(r&0xff);f[6+n]=(byte)(r>>8);f[7+n]=0x1F;f[8+n]=(byte)0xF1;return f;}
  static void check(boolean ok,String m){ if(!ok){System.out.println("FAIL "+m);System.exit(1);} System.out.println("ok "+m);}
  public static void main(String[] a){
    String s=Tap.describe(fr(0x32,2,0,1,2,3,4));
    check(s.startsWith("1aa1320205")&&s.contains("00********")&&s.contains("crc=lohi")&&s.contains("lock_data_len=5"),"lock pin+crc masked: "+s);
    check(!s.contains("01020304"),"pin digits absent");
    String q=Tap.describe(fr(0x67,1,9,8,7,6));
    check(q.contains("1aa1670104************")&&q.endsWith("1ff1 crc=lohi"),"0x67 masked: "+q);
    String m=Tap.describe(fr(0x20,1,0,0x30,0,80,1,2,3,4,5,6,7,8));
    check(m.contains("crc=lohi")&&!m.contains("**"),"meter1 clear: "+m);
    byte[] two=new byte[18]; byte[] h=fr(0x10,2); System.arraycopy(h,0,two,0,9); System.arraycopy(h,0,two,9,9);
    check(Tap.describe(two).split("\\|").length==2,"two frames: "+Tap.describe(two));
    check(Tap.describe(new byte[]{0x1A,(byte)0xA1,0x67,1,4,9,9}).equals("nonframe_len=7"),"partial frame only length");
    byte[] bad=fr(0x30,2,1); bad[6]^=1; check(Tap.describe(bad).startsWith("badcrc cmd=30 tipo=02 len=1"),"bad crc: header only: "+Tap.describe(bad));
    check(Tap.describe(null).equals("null"),"null");
    // Codex R1 P1: LEN of a heartbeat forged to swallow a LOCK frame
    byte[] hb=fr(0x10,2); byte[] lk=fr(0x32,2,1,1,2,3,4); byte[] cat=new byte[hb.length+lk.length];
    System.arraycopy(hb,0,cat,0,hb.length); System.arraycopy(lk,0,cat,hb.length,lk.length); cat[4]=(byte)(lk.length);
    String sw=Tap.describe(cat);
    check(!sw.contains("01020304")&&!sw.matches(".*[0-9a-f]{8}.*"),"swallowed lock not printed: "+sw);
    // valid-CRC outer frame that embeds a LOCK start in its payload
    int[] inner=new int[lk.length]; for(int i=0;i<lk.length;i++) inner[i]=lk[i]&0xff;
    String em=Tap.describe(fr(0x10,2,inner));
    check(em.startsWith("embedded")&&!em.contains("01020304"),"embedded lock masked: "+em);
  }
}
