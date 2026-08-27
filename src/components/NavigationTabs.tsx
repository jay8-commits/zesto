import React from 'react';
import { ZestoTab } from '../types.ts';
import { Settings, Eye, Activity, Video } from 'lucide-react';

interface NavigationTabsProps {
  selectedTab: ZestoTab;
  onSelectTab: (tab: ZestoTab) => void;
}

export const NavigationTabs: React.FC<NavigationTabsProps> = ({
  selectedTab,
  onSelectTab
}) => {
  const tabs = [
    { id: ZestoTab.STREAM_CONFIG, label: 'Config', icon: Settings },
    { id: ZestoTab.STREAM_PREVIEW, label: 'Preview', icon: Eye },
    { id: ZestoTab.DIAGNOSTICS, label: 'Diagnostics', icon: Activity },
    { id: ZestoTab.TARGET_COMPAT, label: 'Target & API', icon: Video }
  ];

  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-[#2D3033] border-t border-[#44474E] sm:relative sm:border-t-0 sm:bg-transparent sm:py-2">
      <div className="max-w-3xl mx-auto flex items-center justify-around sm:justify-center sm:gap-2 px-2 py-1 sm:py-0">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          const isSelected = selectedTab === tab.id;
          return (
            <button
              key={tab.id}
              onClick={() => onSelectTab(tab.id)}
              className={`flex flex-col sm:flex-row items-center sm:gap-2 px-4 py-2 rounded-xl transition-all ${
                isSelected
                  ? 'text-[#D0BCFF] font-semibold bg-[#35393E] sm:bg-[#D0BCFF]/10 sm:border sm:border-[#D0BCFF]/30 shadow-inner'
                  : 'text-[#8E919A] hover:text-[#E2E2E6] hover:bg-[#35393E]/50'
              }`}
            >
              <div
                className={`p-1 rounded-lg ${
                  isSelected ? 'bg-[#D0BCFF] text-[#381E72]' : ''
                }`}
              >
                <Icon className="w-5 h-5" />
              </div>
              <span className="text-xs tracking-wide">{tab.label}</span>
            </button>
          );
        })}
      </div>
    </nav>
  );
};
