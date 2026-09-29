package com.r36s.usb0manager

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private val pool=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var last=""
    private val stamp get()=SimpleDateFormat("HH:mm:ss",Locale.US).format(Date())

    override fun onCreate(b:Bundle?) { super.onCreate(b); buildUi(); refresh(); handler.postDelayed(object:Runnable{override fun run(){refresh();handler.postDelayed(this,3000)}},3000) }

    private fun buildUi(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(8,8,8,8)}
        status=TextView(this).apply{textSize=15f;setPadding(6,6,6,10);text="USB0 Manager"}
        root.addView(status,LinearLayout.LayoutParams(-1,-2))
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        button(body,"AUTO CONFIGURE / RECOVER"){runAction("AUTO",AUTO)}
        button(body,"TEST CONNECTION"){runAction("TEST",TEST)}
        button(body,"ARP / NEIGHBOR FIX"){runAction("ARP",ARP_FIX)}
        button(body,"USB / RNDIS RECOVERY"){runAction("USB",USB_RECOVER)}
        button(body,"SAFE ROUTE REPAIR"){runAction("ROUTE",ROUTE_REPAIR)}
        button(body,"FULL DIAGNOSTIC"){runAction("DIAG",DIAG)}
        button(body,"RESTORE LAST BACKUP"){runAction("RESTORE",RESTORE)}
        button(body,"SAVE REPORT"){runAction("REPORT",DIAG)}
        log=TextView(this).apply{textSize=11f;setTextIsSelectable(true);setPadding(6,10,6,30)}
        body.addView(log,LinearLayout.LayoutParams(-1,0,1f))
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        append("USB0 Manager v1.4 — aggressive RNDIS/ARP recovery enabled")
    }
    private fun button(parent:LinearLayout,text:String,fn:()->Unit){
        val b=Button(this).apply{this.text=text;setOnClickListener{fn()};minHeight=52}
        parent.addView(b,LinearLayout.LayoutParams(-1,-2))
    }
    private fun refresh(){ pool.submit{ val x=runSu(STATUS); val snap=x.substringAfter("=== STATUS ===",""); if(last.isNotEmpty()&&snap!=last) append("[$stamp] usb0 changed\n$x"); last=snap; runOnUiThread{status.text=summary(x)} } }
    private fun summary(s:String):String{ fun v(k:String)=s.lines().firstOrNull{it.startsWith("$k=")}?.substringAfter('=')?:"-"; return "USB0 ${v("LINK")} | IP ${v("IP")} | GW ${v("GW")}\nGW ${v("GWTEST")} | NET ${v("INET")} | DNS ${v("DNS")}" ) }
    private fun runAction(name:String,script:String){ pool.submit{ val o=runSu(script); append("\n[$stamp] $name\n$o"); if(name=="REPORT") saveReport(o); refresh() } }
    private fun saveReport(text:String){val d=File(getExternalFilesDir(null),"reports");d.mkdirs();val f=File(d,"usb0-${System.currentTimeMillis()}.txt");f.writeText(text);append("Report: ${f.absolutePath}")}
    private fun runSu(script:String):String=try{val p=Runtime.getRuntime().exec(arrayOf("su","-c",script));val o=p.inputStream.bufferedReader().readText();val e=p.errorStream.bufferedReader().readText();p.waitFor();o+if(e.isNotBlank())"\n[stderr]\n$e" else ""}catch(e:Exception){"ROOT ERROR: ${e.message}"}
    private fun append(x:String){runOnUiThread{log.append("\n$x")}}

    companion object {
        private const val STATUS="""IF=usb0
 echo \"=== STATUS ===\"
 LINK=$(cat /sys/class/net/$IF/operstate 2>/dev/null || echo down); echo \"LINK=$LINK\"
 IP=$(ip -4 -o addr show dev $IF 2>/dev/null|awk '{print $4;exit}'); echo \"IP=${IP:--}\"
 GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}'); [ -n \"$GW\" ]||GW=$(ip route show dev $IF 2>/dev/null|awk '/^default via /{print $3;exit}'); echo \"GW=${GW:--}\"
 ip route get 1.1.1.1 2>/dev/null|grep -q \"dev $IF\"&&echo ROUTE=OK||echo ROUTE=FAIL
 [ -n \"$GW\" ]&&ping -c1 -W2 -I $IF $GW >/dev/null 2>&1&&echo GWTEST=OK||echo GWTEST=FAIL
 ping -c1 -W3 -I $IF 1.1.1.1 >/dev/null 2>&1&&echo INET=OK||echo INET=FAIL
 ping -c1 -W5 -I $IF google.com >/dev/null 2>&1&&echo DNS=OK||echo DNS=FAIL
"""
        private const val AUTO="""IF=usb0
D=/data/local/tmp/usb0-manager; mkdir -p $D
# Back up all state before aggressive changes.
date > $D/auto.started
getprop > $D/getprop.before
ip addr show > $D/ipaddr.before
ip route show table all > $D/routes.before
ip rule show > $D/rules.before
ip neigh show > $D/neigh.before
cat /proc/net/arp > $D/proc-arp.before 2>/dev/null

echo '=== 1. USB/RNDIS DISCOVERY ==='
ls -l /sys/class/net/$IF 2>&1 || true
readlink -f /sys/class/net/$IF/device/driver 2>&1 || true
cat /sys/class/net/$IF/address 2>&1 || true
cat /sys/class/net/$IF/operstate 2>&1 || true

echo '=== 2. INTERFACE UP ==='
ip link set dev $IF up 2>&1 || true

GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
[ -n "$GW" ]||GW=$(ip route show dev $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
echo "gateway=$GW"

# Try to populate ARP/ND normally first.
if [ -n "$GW" ]; then
  ip neigh del $GW dev $IF 2>/dev/null || true
  ip neigh replace $GW dev $IF nud probe 2>/dev/null || true
  ping -c3 -W2 -I $IF $GW 2>&1 || true
fi

echo '=== 3. DISCOVER PEER MAC ==='
MAC=$(ip neigh show dev $IF 2>/dev/null|awk -v g="$GW" '$1==g && $5 ~ /^[0-9a-fA-F:]+$/ {print $5;exit}')
[ -n "$MAC" ]||MAC=$(cat /proc/net/arp 2>/dev/null|awk -v g="$GW" '$1==g && $4 ~ /^[0-9a-fA-F:]+$/ {print $4;exit}')
echo "peer_mac=${MAC:--}"
if [ -n "$MAC" ] && [ -n "$GW" ]; then
  echo 'Installing verified peer MAC into kernel neighbor table.'
  ip neigh replace $GW lladdr $MAC dev $IF nud permanent 2>&1 || true
  if command -v busybox >/dev/null 2>&1; then busybox arp -i $IF -s $GW $MAC 2>&1 || true; fi
fi

echo '=== 4. RETEST GATEWAY ==='
[ -n "$GW" ]&&ping -c5 -W2 -I $IF $GW 2>&1 || true

echo '=== 5. USB CONFIG INSPECTION ==='
getprop sys.usb.config; getprop sys.usb.state; getprop persist.sys.usb.config
ls -l /sys/class/android_usb/android0 2>/dev/null || true

# Only if usb0 is absent, try RNDIS gadget recovery. This is deliberately
# last-resort because changing gadget functions can disconnect USB.
if [ ! -d /sys/class/net/$IF ]; then
  echo 'usb0 absent: attempting RNDIS gadget recovery.'
  svc usb setFunctions rndis 2>&1 || true
  setprop sys.usb.config rndis 2>/dev/null || true
  sleep 2
  ip link show dev $IF 2>&1 || true
else
  echo 'usb0 present: leaving gadget configuration untouched.'
fi

echo '=== 6. FINAL TEST ==='
ping -c3 -W3 -I $IF 1.1.1.1 2>&1 || true
ping -c2 -W5 -I $IF google.com 2>&1 || true
ip neigh show dev $IF 2>&1
"""
        private const val ARP_FIX="""IF=usb0
GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
[ -n "$GW" ]||GW=$(ip route show dev $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
echo "gateway=$GW"
ip neigh show dev $IF 2>&1
MAC=$(ip neigh show dev $IF 2>/dev/null|awk -v g="$GW" '$1==g && $5 ~ /^[0-9a-fA-F:]+$/ {print $5;exit}')
[ -n "$MAC" ]||MAC=$(cat /proc/net/arp 2>/dev/null|awk -v g="$GW" '$1==g && $4 ~ /^[0-9a-fA-F:]+$/ {print $4;exit}')
if [ -n "$GW" ] && [ -n "$MAC" ]; then
 echo "Installing $GW -> $MAC"
 ip neigh replace $GW lladdr $MAC dev $IF nud permanent
 command -v busybox >/dev/null 2>&1&&busybox arp -i $IF -s $GW $MAC 2>&1||true
 ping -c5 -W2 -I $IF $GW
else
 echo 'No peer MAC was learned. A static ARP entry cannot be safely created without the TCL peer MAC.'
 echo 'The app will not use its own usb0 MAC as the peer MAC.'
fi
"""
        private const val USB_RECOVER="""echo '=== USB/RNDIS RECOVERY ==='
D=/data/local/tmp/usb0-manager; mkdir -p $D
getprop > $D/getprop.usb.before
printf 'sys.usb.config=';getprop sys.usb.config
printf 'sys.usb.state=';getprop sys.usb.state
printf 'persist.sys.usb.config=';getprop persist.sys.usb.config
printf 'usb0=';ip link show usb0 2>&1
printf 'driver=';readlink -f /sys/class/net/usb0/device/driver 2>&1
if [ -d /sys/class/net/usb0 ]; then echo 'usb0 already exists; toggling gadget mode is skipped to protect the live host link.'; else
 echo 'usb0 missing; attempting rndis function.'
 svc usb setFunctions rndis 2>&1 || true
 setprop sys.usb.config rndis 2>&1 || true
 sleep 3
 ip link show usb0 2>&1 || true
fi
"""
        private const val ROUTE_REPAIR="""IF=usb0
D=/data/local/tmp/usb0-manager;mkdir -p $D
ip route show table $IF > $D/routes.repair.before 2>/dev/null
GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
[ -n "$GW" ]||GW=$(ip route show dev $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
if [ -n "$GW" ]; then ip route replace default via $GW dev $IF table $IF; echo "default via $GW dev $IF"; else echo 'No gateway.';fi
ip route get 1.1.1.1 2>&1
"""
        private const val RESTORE="""D=/data/local/tmp/usb0-manager
if [ -s $D/routes.before ]; then
 echo 'Restoring recorded default route.'
 GW=$(awk '/^default via /{print $3;exit}' $D/routes.before)
 [ -n "$GW" ]&&ip route replace default via $GW dev usb0 table usb0
else echo 'No route backup.';fi
"""
        private const val TEST="""IF=usb0
GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}')
[ -n "$GW" ]&&ping -c5 -W2 -I $IF $GW 2>&1||true
ping -c5 -W3 -I $IF 1.1.1.1 2>&1
ping -c3 -W5 -I $IF google.com 2>&1
"""
        private const val DIAG="""IF=usb0
date;id
echo '--- link ---';ip link show dev $IF 2>&1
echo '--- driver ---';readlink -f /sys/class/net/$IF/device/driver 2>&1
echo '--- addr ---';ip -4 addr show dev $IF 2>&1
echo '--- routes ---';ip route show dev $IF 2>&1;ip route show table $IF 2>&1
echo '--- rules ---';ip rule show 2>&1
echo '--- neigh ---';ip neigh show dev $IF 2>&1;cat /proc/net/arp 2>&1
echo '--- usb ---';getprop sys.usb.config;getprop sys.usb.state;getprop persist.sys.usb.config
echo '--- driver/modules ---';cat /proc/modules 2>/dev/null|grep -i rndis || true
echo '--- counters ---';cat /sys/class/net/$IF/statistics/rx_packets 2>/dev/null;cat /sys/class/net/$IF/statistics/tx_packets 2>/dev/null
echo '--- tests ---';GW=$(ip route show table $IF 2>/dev/null|awk '/^default via /{print $3;exit}');[ -n "$GW" ]&&ping -c3 -W2 -I $IF $GW 2>&1;ping -c3 -W3 -I $IF 1.1.1.1 2>&1;ping -c2 -W5 -I $IF google.com 2>&1
"""
    }
}
