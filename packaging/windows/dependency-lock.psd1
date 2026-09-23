@{
    BouncyCastleVersion = '1.86'
    BrotliDecoderVersion = '0.1.2'
    ZstdJniVersion = '1.5.7-12'
    Artifacts = @(
        @{
            Name = 'bcprov'
            FileName = 'bcprov.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcprov-jdk18on/1.86/bcprov-jdk18on-1.86.jar'
            Sha256 = '2af190b300cbb0b35e248ccf5f4a06b6072030aeb3da7a98ec73abe5b4cb371f'
        }
        @{
            Name = 'bcpkix'
            FileName = 'bcpkix.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcpkix-jdk18on/1.86/bcpkix-jdk18on-1.86.jar'
            Sha256 = '8d8b41a4b149bdae8d331f059a578870954d1e3ff29f8269a9eb3ee19a7e4ef7'
        }
        @{
            Name = 'bcutil'
            FileName = 'bcutil.jar'
            Url = 'https://repo.maven.apache.org/maven2/org/bouncycastle/bcutil-jdk18on/1.86/bcutil-jdk18on-1.86.jar'
            Sha256 = '1c268e15f785aafb1e4670d8d6e5f856f69475e96c162dadcd1ff9b350e9139f'
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
            Url = 'https://repo.maven.apache.org/maven2/com/github/luben/zstd-jni/1.5.7-12/zstd-jni-1.5.7-12.jar'
            Sha256 = '33661c9439a0898c4cc844d675bef56f6ea72e11079173c3cfc8f352c6e73f0c'
        }
    )
}

