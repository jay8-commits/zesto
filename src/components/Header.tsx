import React from 'react';
import { ZestoUiState } from '../types.ts';

interface HeaderProps {
  uiState: ZestoUiState;
  onOpenHarness: () => void;
}

export const Header: React.FC<HeaderProps> = ({ uiState, onOpenHarness }) => {
  const isSpinning = uiState.isConnected || uiState.isDecoding;

  return (
    <header className="sticky top-0 z-40 bg-[#1A1C1E] border-b border-[#44474E]/40 px-4 py-3 sm:px-6">
      <div className="max-w-7xl mx-auto flex items-center justify-between">
        {/* Brand Logo & Name */}
        <div className="flex items-center space-x-3">
          <div className="w-9 h-9 rounded-xl bg-[#D0BCFF] flex items-center justify-center shadow-md shadow-[#381E72]/30">
            <div
              className={`w-5 h-5 rounded-full border-[3px] border-[#381E72] ${
                isSpinning ? 'animate-spin' : ''
              }`}
              style={{ animationDuration: '3.5s' }}
            />
          </div>
          <div>
            <div className="flex items-center space-x-2">
              <h1 className="text-xl font-bold tracking-tight text-[#E2E2E6]">Zesto</h1>
              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-[#2D3033] text-[#D0BCFF] border border-[#44474E]/50">
                v1.0
              </span>
            </div>
            <p className="text-xs text-[#C4C6CF] hidden sm:block">
              Camera Virtualization & RTSP Pipeline
            </p>
          </div>
        </div>

        {/* Right Status Badges & Quick Action */}
        <div className="flex items-center space-x-3">
          <button
            onClick={onOpenHarness}
            className="hidden md:flex items-center space-x-1.5 px-3 py-1.5 rounded-lg bg-[#2D3033] hover:bg-[#35393E] text-[#D0BCFF] text-xs font-semibold border border-[#44474E] transition-colors"
          >
            <span>Live Camera Harness</span>
          </button>

          {/* Service Status Badge */}
          <div className="flex items-center space-x-2 bg-[#2D3033] border border-[#44474E] rounded-md px-2.5 py-1">
            <span className="text-[10px] font-mono font-bold tracking-wide text-[#34D399]">
              {uiState.isServiceRunning
                ? 'SERVICE RUNNING'
                : uiState.isConnected
                ? 'LIVE STREAM'
                : 'READY'}
            </span>
            <div
              className={`w-2.5 h-2.5 rounded-full ${
                uiState.isConnected || uiState.isDecoding
                  ? 'bg-[#34D399] animate-pulse shadow-[0_0_8px_#34D399]'
                  : uiState.isConnecting
                  ? 'bg-[#FBBF24] animate-ping'
                  : 'bg-[#64748B]'
              }`}
            />
          </div>
        </div>
      </div>
    </header>
  );
};
