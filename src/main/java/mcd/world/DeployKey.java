package mcd.world;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import java.io.File;
import java.net.InetSocketAddress;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/**
 * 월드 repo 하나에 등록하는 SSH deploy key. 이 키만 있으면 GitHub 계정 없이 그 repo를 읽고 쓸 수 있다.
 * ed25519는 MINA sshd 2.x에서 추가 라이브러리가 필요해서 JDK가 바로 지원하는 ECDSA P-256을 쓴다.
 */
public final class DeployKey {

    /**
     * GitHub SSH 호스트 키의 SHA256 지문(https://api.github.com/meta, 2026-10-17 확인). ssh.github.com:443도 같은 키를 쓴다.
     * 사용자 PC의 ~/.ssh/known_hosts에 기대지 않으려고 코드에 고정한다. GitHub가 키를 바꾸면 이 값도 바꿔야 한다.
     */
    private static final Set<String> GITHUB_HOST_KEYS = Set.of(
            "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU",
            "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM",
            "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s");

    private final KeyPair keys;
    private final SshdSessionFactory sessions;

    private DeployKey(KeyPair keys) {
        this.keys = keys;
        // ~/.ssh(설정, 키, known_hosts)를 읽지 않고 이 키와 GitHub 호스트 키만 쓴다. 없는 폴더를 가리켜도 된다.
        File noSsh = new File(System.getProperty("java.io.tmpdir"), "mcd-no-ssh");
        this.sessions = new SshdSessionFactoryBuilder()
                .setHomeDirectory(noSsh)
                .setSshDirectory(noSsh)
                .setConfigStoreFactory((home, config, user) -> null)
                .setPreferredAuthentications("publickey")
                .setDefaultKeysProvider(dir -> List.of(keys))
                .setServerKeyDatabase((home, ssh) -> new GitHubHostKeys())
                .build(new JGitKeyCache());
    }

    public static DeployKey generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return new DeployKey(generator.generateKeyPair());
    }

    /** {@link #encode}의 결과에서 키를 되살린다. */
    public static DeployKey decode(String encoded) throws GeneralSecurityException {
        String[] parts = encoded.trim().split("\\.");
        if (parts.length != 2) throw new GeneralSecurityException("deploy key 형식이 아니다");
        Base64.Decoder b64 = Base64.getUrlDecoder();
        KeyFactory factory = KeyFactory.getInstance("EC");
        try {
            return new DeployKey(new KeyPair(
                    factory.generatePublic(new X509EncodedKeySpec(b64.decode(parts[1]))),
                    factory.generatePrivate(new PKCS8EncodedKeySpec(b64.decode(parts[0])))));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("deploy key 형식이 아니다", e);
        }
    }

    /** 개인키와 공개키를 문자열 하나로. 초대 코드와 로컬 설정에 넣는다. */
    public String encode() {
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(keys.getPrivate().getEncoded()) + "." + b64.encodeToString(keys.getPublic().getEncoded());
    }

    /** GitHub repo의 Deploy keys 화면에 붙여 넣을 공개키 한 줄. */
    public String publicKeyLine() {
        return PublicKeyEntry.toString(keys.getPublic()) + " mcd";
    }

    /** git 명령(fetch, push, clone)이 이 키로 SSH 접속하게 한다. */
    public TransportConfigCallback transport() {
        return transport -> {
            if (transport instanceof SshTransport ssh) {
                ssh.setSshSessionFactory(sessions);
            }
        };
    }

    static boolean isGitHubHostKey(PublicKey key) {
        return GITHUB_HOST_KEYS.contains(KeyUtils.getFingerPrint(key));
    }

    private static final class GitHubHostKeys implements ServerKeyDatabase {
        @Override
        public List<PublicKey> lookup(String connectAddress, InetSocketAddress remoteAddress, Configuration config) {
            return List.of();
        }

        @Override
        public boolean accept(String connectAddress, InetSocketAddress remoteAddress, PublicKey serverKey,
                              Configuration config, CredentialsProvider provider) {
            return isGitHubHostKey(serverKey);
        }
    }
}
