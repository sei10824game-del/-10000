#include "McCamBridge.h"

#include "Functions.h"
#include "Logging.h"

#include <Glacier/SGameUpdateEvent.h>
#include <Glacier/ZCameraEntity.h>
#include <Glacier/ZGameLoopManager.h>

#include <cstdio>
#include <winsock2.h>
#include <ws2tcpip.h>

#pragma comment(lib, "Ws2_32.lib")

// Camera -> Minecraft, one text datagram per frame: "x y z fx fy fz\n".
// Glacier is right-handed, Y up, camera looks down -Z: the same handedness as Minecraft
// (x east, y up, z south), so position goes through unchanged and forward = -ZAxis.
// ponytail: UDP fire-and-forget, no versioning/handshake; add when a second message type is needed.
static constexpr unsigned short k_Port = 27015;

McCamBridge::~McCamBridge() {
    const ZMemberDelegate<McCamBridge, void(const SGameUpdateEvent&)> s_Delegate(this, &McCamBridge::OnFrameUpdate);
    Globals::GameLoopManager->UnregisterFrameUpdate(s_Delegate, 1, EUpdateMode::eUpdatePlayMode);

    if (m_Socket != ~0ull) {
        closesocket(static_cast<SOCKET>(m_Socket));
        WSACleanup();
    }
}

void McCamBridge::OnEngineInitialized() {
    WSADATA s_Wsa;
    if (WSAStartup(MAKEWORD(2, 2), &s_Wsa) != 0) {
        Logger::Error("[McCamBridge] WSAStartup failed.");
        return;
    }

    const SOCKET s_Sock = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP);
    if (s_Sock == INVALID_SOCKET) {
        Logger::Error("[McCamBridge] socket() failed: {}", WSAGetLastError());
        return;
    }
    m_Socket = s_Sock;

    const ZMemberDelegate<McCamBridge, void(const SGameUpdateEvent&)> s_Delegate(this, &McCamBridge::OnFrameUpdate);
    Globals::GameLoopManager->RegisterFrameUpdate(s_Delegate, 1, EUpdateMode::eUpdatePlayMode);

    Logger::Info("[McCamBridge] Sending camera to 127.0.0.1:{}.", k_Port);
}

void McCamBridge::OnFrameUpdate(const SGameUpdateEvent&) {
    if (m_Socket == ~0ull) return;

    static unsigned s_Frame = 0;
    static bool s_WarnedNull = false;

    const auto s_Camera = Functions::GetCurrentCamera->Call();
    if (!s_Camera) {
        if (!s_WarnedNull) {
            s_WarnedNull = true;
            Logger::Warn("[McCamBridge] GetCurrentCamera() returned null (will retry quietly).");
        }
        return;
    }

    const SMatrix s_M = s_Camera->GetObjectToWorldMatrix();

    char s_Buf[160];
    const int s_Len = snprintf(s_Buf, sizeof(s_Buf), "%.4f %.4f %.4f %.5f %.5f %.5f\n",
        s_M.Trans.x, s_M.Trans.y, s_M.Trans.z,
        -s_M.ZAxis.x, -s_M.ZAxis.y, -s_M.ZAxis.z);

    sockaddr_in s_To = {};
    s_To.sin_family = AF_INET;
    s_To.sin_port = htons(k_Port);
    inet_pton(AF_INET, "127.0.0.1", &s_To.sin_addr);

    const int s_Sent = sendto(static_cast<SOCKET>(m_Socket), s_Buf, s_Len, 0, reinterpret_cast<sockaddr*>(&s_To), sizeof(s_To));

    // diagnostics: first frame, then roughly every 5 s at 60 fps
    if (s_Frame++ % 300 == 0) {
        Logger::Info("[McCamBridge] sendto={} (len {}, err {}) {}", s_Sent, s_Len, s_Sent < 0 ? WSAGetLastError() : 0, s_Buf);
    }
}

DEFINE_ZHM_PLUGIN(McCamBridge);
