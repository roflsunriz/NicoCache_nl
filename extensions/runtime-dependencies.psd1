@{
    SchemaVersion = 3
    Dependencies = @(
        @{
            Id = 'temurin'
            DisplayName = 'Eclipse Temurin OpenJDK'
            Provider = 'Adoptium'
            ManagedPath = 'runtime'
            Executable = 'bin\java'
            WindowsExecutable = 'bin\java.exe'
            VersionArguments = @('-version')
            VersionPattern = 'version "(?<version>[^"]+)"'
            UpdateMode = 'AfterExit'
            ImageType = 'jre'
            PlatformDetection = 'Runtime'
            SupportedLtsVersions = @(17, 21)
            RecommendedLtsVersion = 21
            LtsDescription = 'LTSは長期間アップデートが提供される安定版です。迷った場合は推奨版を選択してください。'
        }
        @{
            Id = 'ffmpeg'
            DisplayName = 'FFmpeg'
            Provider = 'BtbNGitHub'
            ManagedPath = 'tools\ffmpeg'
            Executable = 'bin\ffmpeg.exe'
            VersionArguments = @('-version')
            VersionPattern = '^ffmpeg version (?<version>\S+)'
            UpdateMode = 'Immediate'
            Repository = 'BtbN/FFmpeg-Builds'
            AssetPattern = '^ffmpeg-master-latest-win64-gpl\.zip$'
        }
        @{
            Id = 'bouncycastle'
            DisplayName = 'Bouncy Castle'
            Provider = 'MavenCentral'
            ManagedPath = 'lib'
            Files = @('bcprov.jar', 'bcpkix.jar', 'bcutil.jar')
            VersionSource = 'packaging\windows\dependency-lock.psd1'
            UpdateMode = 'Immediate'
            MavenGroupPath = 'org/bouncycastle'
            MavenArtifacts = @('bcprov-jdk18on', 'bcpkix-jdk18on', 'bcutil-jdk18on')
        }
        @{
            Id = 'ant'
            DisplayName = 'Apache Ant'
            Provider = 'ApacheDistribution'
            ManagedPath = 'tools\ant'
            Executable = 'bin\ant.bat'
            VersionArguments = @('-version')
            VersionPattern = 'Apache Ant\(TM\) version (?<version>\S+)'
            UpdateMode = 'Immediate'
            DistributionUri = 'https://downloads.apache.org/ant/binaries/'
        }
        @{
            Id = '7zip'
            DisplayName = '7-Zip'
            Provider = 'GitHubRelease'
            ManagedPath = 'tools\7zip'
            Executable = '7za.exe'
            VersionArguments = @()
            VersionPattern = '7-Zip\s+(?<version>[0-9.]+)'
            UpdateMode = 'Immediate'
            Repository = 'ip7z/7zip'
            AssetPattern = '^7z[0-9]+-extra\.7z$'
            BootstrapPattern = '^7zr\.exe$'
        }
    )
}