#define AppName "Cam Watchman"
#define AppPublisher "Cam Watchman"
#define AppExeName "CamWatchman.exe"
#ifndef AppVersion
  #define AppVersion "0.1.0"
#endif
#ifndef StageDir
  #define StageDir "..\dist\installer-stage\CamWatchman"
#endif

[Setup]
AppId={{B83A6E55-95C2-49AF-9B0E-CC5E0A1B6F30}
AppName={#AppName}
AppVersion={#AppVersion}
AppPublisher={#AppPublisher}
DefaultDirName={localappdata}\Programs\CamWatchman
DefaultGroupName=Cam Watchman
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
UninstallDisplayName=Cam Watchman
UninstallDisplayIcon={app}\{#AppExeName}
OutputDir=..\dist
OutputBaseFilename=CamWatchman-Setup-{#AppVersion}-win64
Compression=lzma2/normal
SolidCompression=yes
WizardStyle=modern
SetupLogging=yes
CloseApplications=yes
RestartApplications=no
Uninstallable=yes

[Languages]
Name: "english"; MessagesFile: "compiler:Default.isl"

[Tasks]
Name: "desktopicon"; Description: "Create a desktop shortcut"; GroupDescription: "Additional shortcuts:"; Flags: unchecked

[Files]
Source: "{#StageDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Cam Watchman"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"
Name: "{autodesktop}\Cam Watchman"; Filename: "{app}\{#AppExeName}"; WorkingDir: "{app}"; Tasks: desktopicon

[Run]
Filename: "{app}\{#AppExeName}"; Description: "Launch Cam Watchman"; Flags: postinstall nowait skipifsilent

[Code]
procedure CurStepChanged(CurStep: TSetupStep);
var
  DataDir: String;
  ConfigFile: String;
  InitialConfig: String;
begin
  if CurStep = ssPostInstall then
  begin
    DataDir := ExpandConstant('{localappdata}\CamWatchman');
    ForceDirectories(DataDir);
    ConfigFile := DataDir + '\cameras.properties';
    { Never overwrite an existing camera configuration during upgrade/reinstall. }
    if not FileExists(ConfigFile) then
    begin
      InitialConfig :=
        '# Cam Watchman first run: no camera configured yet.' + #13#10 +
        '# Add your camera in the app:  Cameras (header) -> + Add camera, then paste its RTSP URL.' + #13#10 +
        '# The app opens the camera manager automatically when no camera is set up.' + #13#10 +
        '# RTSP URL format:  rtsp://[username:password@]camera-ip:port/stream-path' + #13#10 +
        'base.data.dir=' + #13#10 +
        'recording.segment.seconds=15' + #13#10 +
        'retention.days=7' + #13#10 +
        'retention.size.gb=40' + #13#10 +
        'retention.min.free.gb=10' + #13#10 +
        'ai.rate.fps=1' + #13#10;
      if not SaveStringToFile(ConfigFile, InitialConfig, False) then
        MsgBox('Could not create the first-run camera configuration. Please contact support.', mbError, MB_OK);
    end;
  end;
end;
