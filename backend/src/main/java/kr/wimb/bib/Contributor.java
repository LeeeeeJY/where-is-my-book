package kr.wimb.bib;

/**
 * 책임표시 한 사람.
 *
 * <p>{@link Lexicons#KEY_ROLES}에 해당하는 역할만 저작 매칭 키에 들어갑니다.
 * 역자는 판본마다 다른데, 역자가 다른 판본이야말로 묶고 싶은 대상이기 때문입니다.
 */
public record Contributor(String name, Role role) {

    public enum Role {
        AUTHOR, ORIGINAL_AUTHOR, TRANSLATOR, EDITOR,
        ILLUSTRATOR, PHOTOGRAPHER, SUPERVISOR, COMMENTATOR, ADAPTER, PLANNER
    }

    public boolean isKeyRole() {
        return role == Role.AUTHOR || role == Role.ORIGINAL_AUTHOR;
    }
}
