@{
    SchemaVersion = 1
    ReleaseManifestUri = 'https://raw.githubusercontent.com/roflsunriz/NicoCache_nl/main/extensions/runtime-dependency-releases.json'
    Dependencies = @(
        @{
            Id = 'temurin'
            DisplayName = 'Eclipse Temurin OpenJDK'
            Kind = 'Directory'
            ManagedPath = 'runtime'
            Executable = 'bin\java.exe'
            VersionArguments = @('-version')
            VersionPattern = 'version "(?<version>[^"]+)"'
            UpdateMode = 'AfterExit'
        }
        @{
            Id = 'ffmpeg'
            DisplayName = 'FFmpeg'
            Kind = 'Directory'
            ManagedPath = 'tools\ffmpeg'
            Executable = 'bin\ffmpeg.exe'
            VersionArguments = @('-version')
            VersionPattern = '^ffmpeg version (?<version>\S+)'
            UpdateMode = 'Immediate'
        }
        @{
            Id = 'bouncycastle'
            DisplayName = 'Bouncy Castle'
            Kind = 'FileSet'
            ManagedPath = 'lib'
            Files = @('bcprov.jar', 'bcpkix.jar', 'bcutil.jar')
            VersionSource = 'packaging\windows\dependency-lock.psd1'
            UpdateMode = 'Immediate'
        }
        @{
            Id = 'ant'
            DisplayName = 'Apache Ant'
            Kind = 'Directory'
            ManagedPath = 'tools\ant'
            Executable = 'bin\ant.bat'
            VersionArguments = @('-version')
            VersionPattern = 'Apache Ant\(TM\) version (?<version>\S+)'
            UpdateMode = 'Immediate'
        }
        @{
            Id = '7zip'
            DisplayName = '7-Zip'
            Kind = 'Directory'
            ManagedPath = 'tools\7zip'
            Executable = '7z.exe'
            VersionArguments = @()
            VersionPattern = '7-Zip\s+(?<version>[0-9.]+)'
            UpdateMode = 'Immediate'
        }
    )
}
