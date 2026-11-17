@{
    BouncyCastleVersion = '1.87'
    BrotliDecoderVersion = '0.1.2'
    ZstdJniVersion = '1.5.7-25'
    Artifacts = @(
        @{
            Name = 'bcprov'
            FileName = 'bcprov.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.87/bcprov-jdk18on-1.87.jar'
            Sha256 = '12d2d3dd82a9ec15e92ec7c7943ee64f6097c6dc12c07e984f1693b77565b9aa'
        }
        @{
            Name = 'bcpkix'
            FileName = 'bcpkix.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.87/bcpkix-jdk18on-1.87.jar'
            Sha256 = 'a44be2660d72ed88673372b8cc2d53a25a5aaf27122fb803ed93f07147536aa1'
        }
        @{
            Name = 'bcutil'
            FileName = 'bcutil.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-jdk18on/1.87/bcutil-jdk18on-1.87.jar'
            Sha256 = 'af46dd381553fdc466cd0c18f8bf2144c7dbcb7337eee3e16cab15e96613ddf0'
        }
        @{
            Name = 'brotli-dec'
            FileName = 'brotli-dec.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/brotli/dec/0.1.2/dec-0.1.2.jar'
            Sha256 = '615c0c3efef990d77831104475fba6a1f7971388691d4bad1471ad84101f6d52'
        }
        @{
            Name = 'zstd-jni'
            FileName = 'zstd-jni.jar'
            Url = 'https://repo.maven.apache.org/maven2/com/github/luben/zstd-jni/1.5.7-25/zstd-jni-1.5.7-25.jar'
            Sha256 = '1ff04cfd265453102c700ba2634b9eb50a3455fee27145c7a6e8a5d0563b1d5f'
        }
    )
}

