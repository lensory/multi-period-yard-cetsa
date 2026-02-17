package util;


import java.io.IOException;

@FunctionalInterface
public interface BiThrowableConsumer<T, U> {
    void accept(T t, U u) throws IOException;
}
