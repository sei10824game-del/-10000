#pragma once

#include "IPluginInterface.h"

class McCamBridge : public IPluginInterface {
public:
    McCamBridge() = default;
    ~McCamBridge() override;

    void OnEngineInitialized() override;

private:
    void OnFrameUpdate(const SGameUpdateEvent& p_UpdateEvent);

    unsigned long long m_Socket = ~0ull; // SOCKET (INVALID_SOCKET)
};

DECLARE_ZHM_PLUGIN(McCamBridge)
