# scaffold.ps1
param(
  [string]$AppDir = ".\app\src\main"
)

$java = Join-Path $AppDir "java\com\m15\deepgramagent"
$res  = Join-Path $AppDir "res"

$dirs = @(
  "$java",
  "$java\ui",
  "$java\ui\theme",
  "$java\data",
  "$java\data\db",
  "$java\data\db\dao",
  "$java\data\model",
  "$java\data\repo",
  "$java\audio",
  "$java\net",
  "$java\net\flux",
  "$java\net\realtime",
  "$java\tts",
  "$java\di"
)

$files = @(
  "$java\App.kt",
  "$java\MainActivity.kt",
  "$java\VoiceAgentViewModel.kt",
  "$java\ui\VoiceAgentScreen.kt",
  "$java\ui\theme\Color.kt",
  "$java\ui\theme\Type.kt",
  "$java\ui\theme\Theme.kt",
  "$java\data\model\Models.kt",
  "$java\data\db\Entities.kt",
  "$java\data\db\dao\MessageDao.kt",
  "$java\data\db\dao\SessionDao.kt",
  "$java\data\db\dao\TranscriptDao.kt",
  "$java\data\db\AppDatabase.kt",
  "$java\data\repo\ConversationRepository.kt",
  "$java\audio\AudioCapture.kt",
  "$java\net\flux\FluxClient.kt",
  "$java\net\flux\FluxClientImpl.kt",
  "$java\net\realtime\RealtimeClient.kt",
  "$java\net\realtime\RealtimeClientImpl.kt",
  "$java\tts\TtsClient.kt",
  "$java\tts\AndroidTtsClient.kt",
  "$java\tts\SmartTts.kt",
  "$java\di\ServiceLocator.kt",
  "$java\BargeInController.kt"
)

$dirs | ForEach-Object { New-Item -ItemType Directory -Force -Path $_ | Out-Null }
$files | ForEach-Object { New-Item -ItemType File -Force -Path $_ | Out-Null }

# Android resources
New-Item -ItemType Directory -Force -Path "$res\values" | Out-Null
New-Item -ItemType File -Force -Path "$res\values\strings.xml" | Out-Null
New-Item -ItemType File -Force -Path "$res\values\themes.xml" | Out-Null
New-Item -ItemType File -Force -Path "$res\values\colors.xml" | Out-Null

Write-Host "Folders and empty files created. Paste the code from ChatGPT into each file."
