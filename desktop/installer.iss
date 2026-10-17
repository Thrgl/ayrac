; Ayraç kurulum programı (Inno Setup 6). build.ps1 derler:
;   ISCC.exe /DAppVer=1.9.0 /DSrcDir=<staging> /DOutDir=desktop\releases installer.iss
; Kullanıcı başına kurulur (yönetici izni gerekmez): %LOCALAPPDATA%\Programs\Ayrac
; Kitaplar, defter ve ayarlar (%APPDATA%\Ayrac) kurulumdan ve kaldırmadan etkilenmez.

#ifndef AppVer
  #define AppVer "0.0.0"
#endif

[Setup]
AppId={{1989DF88-D60F-456A-A8F4-F047291B96C1}
AppName=Ayraç
AppVersion={#AppVer}
AppVerName=Ayraç {#AppVer}
AppPublisher=Tahir
VersionInfoVersion={#AppVer}
DefaultDirName={localappdata}\Programs\Ayrac
DisableDirPage=yes
DisableProgramGroupPage=yes
DisableReadyPage=yes
PrivilegesRequired=lowest
OutputDir={#OutDir}
OutputBaseFilename=Ayrac-Setup-{#AppVer}
SetupIconFile={#SrcDir}\ayrac.ico
UninstallDisplayIcon={app}\Ayrac.exe
UninstallDisplayName=Ayraç
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
ChangesAssociations=yes
; A running Ayraç is closed before files are replaced (Restart Manager), and reopened afterwards.
CloseApplications=force
RestartApplications=no
ShowLanguageDialog=no

[Languages]
Name: "tr"; MessagesFile: "compiler:Languages\Turkish.isl"

[Tasks]
Name: "desktopicon"; Description: "Masaüstüne kısayol koy"; GroupDescription: "Kısayollar:"
Name: "epubassoc"; Description: ".epub dosyalarının ""Birlikte aç"" menüsüne Ayraç'ı ekle"; GroupDescription: "Dosyalar:"

[Files]
Source: "{#SrcDir}\Ayrac.exe"; DestDir: "{app}"; Flags: ignoreversion
Source: "{#SrcDir}\dict\en-tr.sqlite"; DestDir: "{app}\dict"; Flags: ignoreversion
Source: "{#SrcDir}\update-source.txt"; DestDir: "{app}"; Flags: ignoreversion skipifsourcedoesntexist

[Icons]
Name: "{userprograms}\Ayraç"; Filename: "{app}\Ayrac.exe"; Comment: "İngilizce EPUB okuyucu"
Name: "{userdesktop}\Ayraç"; Filename: "{app}\Ayrac.exe"; Comment: "İngilizce EPUB okuyucu"; Tasks: desktopicon

[Registry]
; Per-user file type, listed under "Open with" for .epub (Windows decides the default app; we don't take it over).
Root: HKCU; Subkey: "Software\Classes\Ayrac.EPUB"; ValueType: string; ValueData: "EPUB kitabı"; Flags: uninsdeletekey; Tasks: epubassoc
Root: HKCU; Subkey: "Software\Classes\Ayrac.EPUB\DefaultIcon"; ValueType: string; ValueData: "{app}\Ayrac.exe,0"; Tasks: epubassoc
Root: HKCU; Subkey: "Software\Classes\Ayrac.EPUB\shell\open\command"; ValueType: string; ValueData: """{app}\Ayrac.exe"" ""%1"""; Tasks: epubassoc
Root: HKCU; Subkey: "Software\Classes\.epub\OpenWithProgids"; ValueType: string; ValueName: "Ayrac.EPUB"; ValueData: ""; Flags: uninsdeletevalue; Tasks: epubassoc
Root: HKCU; Subkey: "Software\Classes\Applications\Ayrac.exe"; ValueType: string; ValueName: "FriendlyAppName"; ValueData: "Ayraç"; Flags: uninsdeletekey; Tasks: epubassoc
Root: HKCU; Subkey: "Software\Classes\Applications\Ayrac.exe\SupportedTypes"; ValueType: string; ValueName: ".epub"; ValueData: ""; Tasks: epubassoc

[Run]
Filename: "{app}\Ayrac.exe"; Description: "Ayraç'ı aç"; Flags: nowait postinstall skipifsilent
; After a silent update started from inside the app, open the new version again.
Filename: "{app}\Ayrac.exe"; Flags: nowait; Check: WizardSilent

[UninstallRun]
Filename: "{cmd}"; Parameters: "/c taskkill /im Ayrac.exe /f"; Flags: runhidden; RunOnceId: "StopAyrac"
