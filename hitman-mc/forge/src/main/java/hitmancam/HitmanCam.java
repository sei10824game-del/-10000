package hitmancam;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * Receives "x y z fx fy fz" (HITMAN camera, Glacier coords = Minecraft axes) on UDP 127.0.0.1:27015
 * and pins the local player's camera to it. Offsets are JVM properties: -Dhitmancam.offx/offy/offz, -Dhitmancam.port.
 */
@Mod("hitmancam")
public class HitmanCam {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PORT = Integer.getInteger("hitmancam.port", 27015);
    private static final double OFF_X = Double.parseDouble(System.getProperty("hitmancam.offx", "0"));
    private static final double OFF_Y = Double.parseDouble(System.getProperty("hitmancam.offy", "100"));
    private static final double OFF_Z = Double.parseDouble(System.getProperty("hitmancam.offz", "0"));
    private static final long STALE_MS = 500;

    private static volatile double[] latest; // x y z fx fy fz
    private static volatile long latestAt;

    public HitmanCam() {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != Dist.CLIENT) return;
        Thread t = new Thread(HitmanCam::listen, "hitmancam-udp");
        t.setDaemon(true);
        t.start();
        MinecraftForge.EVENT_BUS.register(this);
    }

    private static void listen() {
        try (DatagramSocket sock = new DatagramSocket(PORT, InetAddress.getLoopbackAddress())) {
            LOGGER.info("hitmancam listening on 127.0.0.1:{}", PORT);
            byte[] buf = new byte[256];
            while (true) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                sock.receive(p);
                String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII).trim().split(" ");
                if (f.length != 6) continue;
                double[] v = new double[6];
                try {
                    for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(f[i]);
                } catch (NumberFormatException e) {
                    continue;
                }
                latest = v;
                latestAt = System.currentTimeMillis();
            }
        } catch (Exception e) {
            LOGGER.error("hitmancam UDP listener stopped", e);
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent e) {
        if (e.phase != TickEvent.Phase.START) return;
        double[] v = latest;
        LocalPlayer pl = Minecraft.getInstance().player;
        if (v == null || pl == null || System.currentTimeMillis() - latestAt > STALE_MS) return;

        // the camera is driven from HITMAN: don't hold the mouse or pause when focus moves there
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();

        double x = v[0] + OFF_X, y = v[1] + OFF_Y, z = v[2] + OFF_Z;
        // forward f -> MC yaw (0 = +z/south, clockwise from above) and pitch (+ = down)
        float yaw = (float) Math.toDegrees(Math.atan2(-v[3], v[5]));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, v[4]))));
        // eye height: the camera is the eye, so lower the feet position by it
        double feetY = y - pl.getEyeHeight();

        pl.setPos(x, feetY, z);
        pl.xo = pl.xOld = x; pl.yo = pl.yOld = feetY; pl.zo = pl.zOld = z;
        pl.setYRot(yaw); pl.yRotO = yaw; pl.setYHeadRot(yaw); pl.yHeadRotO = yaw;
        pl.setXRot(pitch); pl.xRotO = pitch;
        pl.setDeltaMovement(0, 0, 0);
        pl.getAbilities().flying = true;
    }
}
