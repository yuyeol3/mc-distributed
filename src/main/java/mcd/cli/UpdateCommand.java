package mcd.cli;

import mcd.update.AppVersion;
import mcd.update.Updater;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

/** `mcd update`와, `play`를 시작할 때의 새 버전 안내. */
final class UpdateCommand {

    private UpdateCommand() {}

    static void run(Consumer<String> log) throws Exception {
        Optional<AppVersion> current = AppVersion.current();
        Optional<Path> installDir = Updater.installDir();
        if (current.isEmpty() || installDir.isEmpty()) {
            throw new IllegalStateException("배포판(mcd-cli.exe)으로 실행할 때만 스스로 업데이트할 수 있습니다.");
        }
        Updater updater = Updater.official();
        Optional<Updater.Release> release = updater.newerThan(current.get());
        if (release.isEmpty()) {
            log.accept("최신 버전(" + current.get() + ")입니다.");
            return;
        }
        log.accept("새 버전 " + release.get().version() + "을(를) 받는 중입니다(지금 " + current.get() + ").");
        Path newApp = updater.download(release.get());
        updater.launchReplacer(newApp, installDir.get(), Optional.empty());
        log.accept("받아서 검증했습니다. 이 폴더의 mcd(GUI 포함)가 모두 끝나면 새 버전으로 바뀝니다.");
    }

    /** 새 버전이 있으면 알리기만 한다. 확인에 실패해도 플레이에는 영향이 없다. */
    static void notifyIfNewer(Consumer<String> log) {
        Optional<AppVersion> current = AppVersion.current();
        if (current.isEmpty()) return;
        try {
            Updater.official().newerThan(current.get()).ifPresent(r ->
                    log.accept("새 버전 " + r.version() + "이(가) 있습니다(지금 " + current.get() + "). 끝낸 뒤 mcd-cli update로 바꾸세요."));
        } catch (Exception ignored) {
            // 네트워크 문제 등은 조용히 넘긴다.
        }
    }
}
