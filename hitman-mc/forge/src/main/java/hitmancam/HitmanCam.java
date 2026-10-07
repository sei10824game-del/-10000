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
 * Receives "x y z fx fy fz" (HITMAN camera, world coords, Z up) on UDP 127.0.0.1:27015
 * and pins the local player's camera to it. Offsets are JVM properties: -Dhitmancam.offx/offy/offz, -Dhitmancam.port.
 */
@Mod("hitmancam")
public class HitmanCam {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PORT = Integer.getInteger("hitmancam.port", 27015);
    private static final double OFF_X = Double.parseDouble(System.getProperty("hitmancam.offx", "0"));
    private static final double OFF_Y = Double.parseDouble(System.getProperty("hitmancam.offy", "-60"));
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
        try (DatagramSocket sock = new DatagramSocket(PORT, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}))) {
            LOGGER.info("hitmancam listening on {}", sock.getLocalSocketAddress());
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
                if (latest == null) LOGGER.info("hitmancam: first camera packet received");
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
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer pl = mc.player;
        if (v == null || pl == null) return;

        // once HITMAN has sent a camera, never hold the mouse or pause: otherwise a paused/unfocused HITMAN stops
        // sending and the mouse can't be handed back to it
        mc.options.pauseOnLostFocus = false;
        // focus loss (clicking HITMAN) opens the pause menu: close it so the view keeps following
        if (mc.screen instanceof net.minecraft.client.gui.screens.PauseScreen) mc.setScreen(null);
        if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
        if (System.currentTimeMillis() - latestAt > STALE_MS) return;

        // HITMAN world is Z-up (x east, y north, z up): MC = (x, z, -y), same for the forward vector
        double x = v[0] + OFF_X, y = v[2] + OFF_Y, z = -v[1] + OFF_Z;
        double fx = v[3], fy = v[5], fz = -v[4];
        // forward -> MC yaw (0 = +z/south, clockwise from above) and pitch (+ = down)
        float yaw = (float) Math.toDegrees(Math.atan2(-fx, fz));
        float pitch = (float) Math.toDegrees(-Math.asin(Math.max(-1, Math.min(1, fy))));
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
