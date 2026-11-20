import { callBridge } from '../../core/bridge.js';
import { clearHistory as clearStoredHistory } from '../chat/history-store.js';
import { saveSettings as persistSettings } from './settings-store.js';
import { state } from '../../core/state.js';
import { speakText } from '../chat/chat.js';
import * as ui from '../../ui/ui.js';

let clearArmed = false;
let clearTimer = null;

const directoryList = document.getElementById('agentDirectories');

function addDirectory({ path = '', writable = false } = {}) {
  const row = document.createElement('div');
  row.className = 'agent-directory';
  const input = document.createElement('input');
  input.value = path;
  input.placeholder = '~/projects/example';
  input.setAttribute('aria-label', '目录路径');
  input.autocomplete = 'off';

  const access = document.createElement('select');
  access.setAttribute('aria-label', '目录权限');
  access.add(new Option('只读', 'read'));
  access.add(new Option('读写', 'write'));
  access.value = writable ? 'write' : 'read';

  const remove = document.createElement('button');
  remove.type = 'button';
  remove.className = 'secondary-btn';
  remove.textContent = '移除';
  remove.addEventListener('click', () => row.remove());

  row.append(input, access, remove);
  directoryList.append(row);
  return input;
}

document.getElementById('addAgentDirectory').addEventListener('click', () => addDirectory().focus());

function directoriesFromForm() {
  const entries = Array.from(directoryList.children, (row) => ({
    path: row.querySelector('input').value.trim().replace(/\/+$/, ''),
    writable: row.querySelector('select').value === 'write'
  })).filter((entry) => entry.path);
  const paths = entries.map((entry) => entry.path);
  if (paths.some((path) => !/^(\/|~\/)/.test(path) || /[\x00-\x1f\x7f]/.test(path) ||
      path.split('/').slice(1).some((part) => !part || part === '.' || part === '..')) ||
      new Set(paths).size !== paths.length) {
    ui.toast('请填写不同的实际目录路径，如 ~/projects/example');
    return null;
  }
  return entries;
}

export function openSettings() {
  ui.els.apiKeyInput.value = state.settings.apiKey || '';
  ui.els.modelSelect.value = state.settings.model;
  ui.els.voiceSelect.value = state.settings.voice;
  ui.els.autoSpeakInput.checked = !!state.settings.autoSpeak;
  ui.els.webSearchInput.checked = !!state.settings.webSearchEnabled;
  ui.els.calculatorInput.checked = !!state.settings.calculatorEnabled;
  ui.els.wakeWordInput.checked = !!state.settings.wakeWordEnabled;
  ui.els.bargeInInput.checked = state.settings.bargeInEnabled !== false;
  ui.els.headsetMicInput.checked = state.settings.headsetMicEnabled === true;
  ui.els.opencodeEnabledInput.checked = state.settings.opencodeEnabled === true;
  ui.els.opencodeWriteInput.checked = state.settings.opencodeWriteEnabled === true;
  ui.els.opencodeSessionInput.value = state.settings.opencodeSessionId || '';
  ui.els.pcProjectInput.value = state.settings.pcProject || '~/Reduction-iterate';
  directoryList.replaceChildren();
  state.settings.agentDirectories.forEach(addDirectory);

  const raw = callBridge('getAppInfo');
  if (typeof raw === 'string' && raw) {
    try {
      const info = JSON.parse(raw);
      if (info.android && info.ttsEngine) ui.els.voiceSelect.closest('label').classList.add('hidden');
      ui.setAppInfo(
        `版本 ${info.version} · Android ${info.android} (API ${info.sdk})\n` +
        `设备 ${info.device}\n` +
        `Key：${info.hasKey ? '已设置' : '未设置'}\n` +
        `电脑：${info.pcUser || ''}@${info.pcHost || '未设置'}:${info.pcPort || 22}\n` +
        (info.ttsEngine ? `朗读引擎：${info.ttsEngine}\n` : '') +
        `音频缓存：${Number(info.cacheMb || 0).toFixed(1)} MB`
      );
    } catch (error) {
      ui.setAppInfo('');
    }
  }
  ui.openSheet(ui.els.settingsSheet, ui.els.settingsBackdrop);
}

function currentSettingsFromForm() {
  const agentDirectories = directoriesFromForm();
  if (!agentDirectories) return null;
  return {
    agentDirectories,
    apiKey: ui.els.apiKeyInput.value.trim(),
    model: ui.els.modelSelect.value,
    voice: ui.els.voiceSelect.value,
    autoSpeak: ui.els.autoSpeakInput.checked,
    webSearchEnabled: ui.els.webSearchInput.checked,
    calculatorEnabled: ui.els.calculatorInput.checked,
    wakeWordEnabled: ui.els.wakeWordInput.checked,
    bargeInEnabled: ui.els.bargeInInput.checked,
    headsetMicEnabled: ui.els.headsetMicInput.checked,
    opencodeEnabled: ui.els.opencodeEnabledInput.checked,
    opencodeWriteEnabled: ui.els.opencodeWriteInput.checked,
    opencodeSessionId: ui.els.opencodeSessionInput.value.trim(),
    pcProject: ui.els.pcProjectInput.value.trim() || '~/Reduction-iterate',
    pcHost: state.settings.pcHost || '',
    pcPort: state.settings.pcPort || 22,
    pcUser: state.settings.pcUser || ''
  };
}

export function saveSettingsForm() {
  const next = currentSettingsFromForm();
  if (!next) return;
  const result = persistSettings(next);
  if (!result.ok) {
    ui.toast('保存失败');
    return;
  }
  state.settings = result.settings;
  ui.updateHint();
  callBridge('setWakeWordEnabled', next.wakeWordEnabled);
  if (next.headsetMicEnabled) callBridge('requestBluetoothPermission');
  ui.closeSheets();
  ui.toast(next.wakeWordEnabled ? '免按住模式已开启，正在加载唤醒词' : '设置已保存');
}

export function testTts() {
  const next = currentSettingsFromForm();
  if (!next) return;
  const result = persistSettings(next);
  if (!result.ok) {
    ui.toast('保存失败');
    return;
  }
  state.settings = result.settings;
  ui.updateHint();
  speakText('你好，我是 Reduction，现在正在测试朗读。');
  ui.toast('正在测试朗读，请确认媒体音量已打开');
}

export function clearAudioCache() {
  const result = callBridge('clearAudioCache');
  ui.toast(typeof result === 'string' ? result : '已清理音频缓存');
}

export function clearChatHistory() {
  if (!clearArmed) {
    clearArmed = true;
    ui.els.clearHistory.textContent = '再点一次确认清空';
    clearTimeout(clearTimer);
    clearTimer = setTimeout(() => {
      clearArmed = false;
      ui.els.clearHistory.textContent = '清空聊天记录';
    }, 3500);
    ui.toast('再点一次即可清空本机聊天记录');
    return;
  }
  clearTimeout(clearTimer);
  clearArmed = false;
  ui.els.clearHistory.textContent = '清空聊天记录';
  clearStoredHistory();
  state.messages = [];
  ui.renderAll();
  ui.closeSheets();
  ui.toast('聊天记录已清空');
}

export function shareDiagnostics() {
  callBridge('shareDiagnostics');
}
