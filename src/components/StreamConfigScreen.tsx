import React from 'react';
import { TransportProtocol, ZestoUiState } from '../types.ts';
import { Play, Square, CheckCircle2, AlertTriangle, Loader2, Sparkles, Radio } from 'lucide-react';

interface StreamConfigScreenProps {
  uiState: ZestoUiState;
  onUrlChange: (url: string) => void;
  onProtocolChange: (protocol: TransportProtocol) => void;
  onResolutionChange: (width: number, height: number) => void;
  onFpsChange: (fps: number) => void;
  onSourceTypeChange?: (source: 'simulated_rtsp' | 'webcam' | 'test_pattern') => void;
  onTestConnection: () => void;
  onConnect: () => void;
  onDisconnect: () => void;
}

export const StreamConfigScreen: React.FC<StreamConfigScreenProps> = ({
  uiState,
  onUrlChange,
  onProtocolChange,
  onResolutionChange,
  onFpsChange,
  onSourceTypeChange,
  onTestConnection,
  onConnect,
  onDisconnect
}) => {
  const isConnected = uiState.isConnected;
  const isConnecting = uiState.isConnecting;
  const isTesting = uiState.isTestingConnection;
  const result = uiState.connectionTestResult;
  const isSuccess = result ? result.startsWith('SUCCESS') : false;

  return (
    <div className="max-w-4xl mx-auto space-y-5 pb-24 sm:pb-8">
      {/* Transport Configuration Card */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[24px] p-5 sm:p-6 shadow-lg">
        <div className="space-y-4">
          <div className="flex items-center justify-between">
            <h2 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
              Transport Configuration
            </h2>
            <div className="flex items-center gap-1.5 text-xs text-[#8E919A] font-mono">
              <Radio className="w-3.5 h-3.5 text-[#D0BCFF]" />
              <span>RTSP Ingestion</span>
            </div>
          </div>

          {/* URL Input and Test Button */}
          <div className="flex flex-col sm:flex-row gap-3">
            <div className="relative flex-1">
              <input
                type="text"
                value={uiState.streamConfig.url}
                onChange={(e) => onUrlChange(e.target.value)}
                placeholder="rtsp://192.168.1.104:8554/live"
                disabled={isConnected || isConnecting}
                className="w-full bg-[#1A1C1E] border border-[#44474E] focus:border-[#D0BCFF] focus:outline-none rounded-2xl px-4 py-3.5 text-sm text-[#E2E2E6] placeholder-[#64748B] font-mono transition-colors disabled:opacity-60"
              />
            </div>

            <button
              onClick={onTestConnection}
              disabled={isTesting || !uiState.streamConfig.url.trim()}
              className="px-6 py-3.5 rounded-2xl bg-[#D0BCFF] hover:bg-[#EADDFF] active:bg-[#B89CF5] disabled:bg-[#44474E] text-[#381E72] disabled:text-gray-400 font-bold text-xs tracking-wider font-mono flex items-center justify-center gap-2 transition-all shadow-md"
            >
              {isTesting ? (
                <>
                  <Loader2 className="w-4 h-4 animate-spin text-[#381E72]" />
                  <span>PROBING...</span>
                </>
              ) : (
                <span>TEST</span>
              )}
            </button>
          </div>

          {/* Connect & Disconnect Buttons */}
          <div className="grid grid-cols-2 gap-3 pt-2">
            <button
              onClick={onConnect}
              disabled={isConnected || isConnecting}
              className={`h-12 rounded-2xl flex items-center justify-center gap-2 font-semibold text-sm transition-all shadow-md ${
                isConnected
                  ? 'bg-[#44474E] text-white opacity-80 cursor-default'
                  : 'bg-[#D0BCFF] hover:bg-[#EADDFF] text-[#381E72] active:scale-[0.99]'
              } disabled:opacity-40 disabled:pointer-events-none`}
            >
              <Play className="w-4 h-4 fill-current" />
              <span>CONNECT</span>
            </button>

            <button
              onClick={onDisconnect}
              disabled={!isConnected && !isConnecting}
              className="h-12 rounded-2xl border border-[#44474E] hover:border-[#EF4444] text-[#EF4444] hover:bg-[#EF4444]/10 active:scale-[0.99] flex items-center justify-center gap-2 font-semibold text-sm transition-all disabled:opacity-30 disabled:pointer-events-none"
            >
              <Square className="w-4 h-4 fill-current" />
              <span>DISCONNECT</span>
            </button>
          </div>
        </div>
      </div>

      {/* Connection Test Result Banner */}
      {result && (
        <div
          className={`p-4 rounded-2xl border flex items-center gap-3 transition-all ${
            isSuccess
              ? 'bg-[#064E3B]/40 border-[#34D399]/50 text-[#A7F3D0]'
              : 'bg-[#7F1D1D]/40 border-[#EF4444]/50 text-[#FECACA]'
          }`}
        >
          {isSuccess ? (
            <CheckCircle2 className="w-5 h-5 text-[#34D399] shrink-0" />
          ) : (
            <AlertTriangle className="w-5 h-5 text-[#EF4444] shrink-0" />
          )}
          <div className="font-mono text-xs leading-relaxed break-all">
            {result}
          </div>
        </div>
      )}

      {/* Source Feed Mode (Web Extension for flexible testing) */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5">
        <div className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
              Video Source Generator / Hardware
            </h3>
            <span className="text-[11px] font-mono text-[#D0BCFF] flex items-center gap-1">
              <Sparkles className="w-3.5 h-3.5" /> Direct Canvas Pipe
            </span>
          </div>

          <div className="flex flex-wrap gap-2.5">
            {[
              { id: 'test_pattern', label: 'Color Bars & Timestamp (Test Pattern)' },
              { id: 'webcam', label: 'Physical Web Camera (Local Sensor)' },
              { id: 'simulated_rtsp', label: 'OBS Studio / RTSP Live Feed' }
            ].map((source) => (
              <button
                key={source.id}
                onClick={() => onSourceTypeChange?.(source.id as any)}
                className={`px-3.5 py-2 rounded-xl text-xs font-medium font-mono transition-colors border ${
                  uiState.inputSourceType === source.id
                    ? 'bg-[#D0BCFF] text-[#381E72] border-[#D0BCFF] font-bold shadow'
                    : 'bg-[#1A1C1E] text-[#C4C6CF] border-[#44474E] hover:border-[#8E919A]'
                }`}
              >
                {source.label}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Transport Protocol */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5">
        <div className="space-y-3">
          <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
            Transport Protocol
          </h3>

          <div className="flex flex-wrap gap-2.5">
            {[
              { id: TransportProtocol.RTSP_TCP, label: 'RTSP / TCP (Reliable / Interleaved)' },
              { id: TransportProtocol.RTSP_UDP, label: 'RTSP / UDP (Low Latency)' },
              { id: TransportProtocol.FUTURE_WEBRTC, label: 'WebRTC (Ultra Low Latency)' }
            ].map((proto) => {
              const isSelected = uiState.streamConfig.protocol === proto.id;
              return (
                <button
                  key={proto.id}
                  onClick={() => onProtocolChange(proto.id)}
                  disabled={isConnected}
                  className={`px-4 py-2 rounded-xl text-xs font-medium font-mono transition-colors border ${
                    isSelected
                      ? 'bg-[#D0BCFF] text-[#381E72] border-[#D0BCFF] font-bold shadow'
                      : 'bg-[#1A1C1E] text-[#C4C6CF] border-[#44474E] hover:border-[#8E919A]'
                  } disabled:opacity-50`}
                >
                  {proto.label}
                </button>
              );
            })}
          </div>
        </div>
      </div>

      {/* Resolution & Framerate */}
      <div className="bg-[#2D3033] border border-[#44474E] rounded-[20px] p-5">
        <div className="space-y-4">
          <h3 className="text-xs font-mono font-bold tracking-wider text-[#C4C6CF] uppercase">
            Target Resolution & Framerate
          </h3>

          {/* Resolution Options */}
          <div className="space-y-2">
            <span className="text-[11px] text-[#8E919A] font-mono">RESOLUTION:</span>
            <div className="flex flex-wrap gap-2.5">
              {[
                { w: 1280, h: 720, label: '720p (1280x720)' },
                { w: 1920, h: 1080, label: '1080p (1920x1080)' },
                { w: 640, h: 480, label: '480p (640x480)' }
              ].map((res) => {
                const isSelected =
                  uiState.streamConfig.targetWidth === res.w &&
                  uiState.streamConfig.targetHeight === res.h;
                return (
                  <button
                    key={`${res.w}x${res.h}`}
                    onClick={() => onResolutionChange(res.w, res.h)}
                    disabled={isConnected}
                    className={`px-4 py-2 rounded-xl text-xs font-medium font-mono transition-colors border ${
                      isSelected
                        ? 'bg-[#D0BCFF] text-[#381E72] border-[#D0BCFF] font-bold shadow'
                        : 'bg-[#1A1C1E] text-[#C4C6CF] border-[#44474E] hover:border-[#8E919A]'
                    } disabled:opacity-50`}
                  >
                    {res.label}
                  </button>
                );
              })}
            </div>
          </div>

          {/* FPS Options */}
          <div className="space-y-2 pt-2">
            <span className="text-[11px] text-[#8E919A] font-mono">TARGET FRAMERATE:</span>
            <div className="flex flex-wrap gap-2.5">
              {[
                { fps: 30, label: '30 FPS' },
                { fps: 60, label: '60 FPS' }
              ].map((f) => {
                const isSelected = uiState.streamConfig.targetFps === f.fps;
                return (
                  <button
                    key={f.fps}
                    onClick={() => onFpsChange(f.fps)}
                    disabled={isConnected}
                    className={`px-4 py-2 rounded-xl text-xs font-medium font-mono transition-colors border ${
                      isSelected
                        ? 'bg-[#D0BCFF] text-[#381E72] border-[#D0BCFF] font-bold shadow'
                        : 'bg-[#1A1C1E] text-[#C4C6CF] border-[#44474E] hover:border-[#8E919A]'
                    } disabled:opacity-50`}
                  >
                    {f.label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};
