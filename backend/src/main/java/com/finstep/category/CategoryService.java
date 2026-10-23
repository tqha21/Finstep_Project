package com.finstep.category;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

@Service
public class CategoryService {
    private final JdbcTemplate jdbc;
    public CategoryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Category(UUID id, String name, CategoryType type, boolean archived, String templateKey) { }
    private record Template(String key, String name, CategoryType type) { }
    private static final List<Template> DEFAULTS = List.of(
        new Template("food","Ăn uống",CategoryType.EXPENSE),
        new Template("transport","Đi lại",CategoryType.EXPENSE),
        new Template("housing","Nhà ở",CategoryType.EXPENSE),
        new Template("shopping","Mua sắm",CategoryType.EXPENSE),
        new Template("health","Sức khỏe",CategoryType.EXPENSE),
        new Template("entertainment","Giải trí",CategoryType.EXPENSE),
        new Template("other_expense","Chi tiêu khác",CategoryType.EXPENSE),
        new Template("salary","Lương",CategoryType.INCOME),
        new Template("other_income","Thu nhập khác",CategoryType.INCOME));
    private Category map(ResultSet rs, int row) throws SQLException {
        return new Category(rs.getObject("id",UUID.class),rs.getString("name"),
            CategoryType.valueOf(rs.getString("type")),rs.getBoolean("is_archived"),rs.getString("template_key"));
    }
    public List<Category> list(UUID owner, boolean includeArchived, CategoryType type) {
        return jdbc.query("SELECT * FROM categories WHERE user_id=? AND (? OR NOT is_archived)"
            + " AND (?::varchar IS NULL OR type=?) ORDER BY created_at,id",this::map,
            owner,includeArchived,type==null?null:type.name(),type==null?null:type.name());
    }
    public Category get(UUID owner, UUID id) {
        return jdbc.query("SELECT * FROM categories WHERE user_id=? AND id=?",this::map,owner,id).stream()
            .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy danh mục."));
    }
    @Transactional
    public Category create(UUID owner,String name,CategoryType type) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO categories(id,user_id,name,type) VALUES(?,?,?,?)",id,owner,name.strip(),type.name());
        return get(owner,id);
    }
    @Transactional
    public Category update(UUID owner,UUID id,String name,Boolean archived) {
        if (name==null && archived==null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Cần cung cấp tên hoặc trạng thái lưu trữ.");
        int changed=jdbc.update("UPDATE categories SET name=coalesce(?,name),is_archived=coalesce(?,is_archived) WHERE user_id=? AND id=?",
            name==null?null:name.strip(),archived,owner,id);
        if (changed==0) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy danh mục.");
        return get(owner,id);
    }
    @Transactional
    public List<Category> defaults(UUID owner) {
        for (Template template:DEFAULTS) {
            jdbc.update("INSERT INTO categories(user_id,name,type,template_key) VALUES(?,?,?,?)"
                + " ON CONFLICT(user_id,template_key) DO NOTHING",owner,template.name(),template.type().name(),template.key());
        }
        // Preserve user renames/archival, including on retries.
        return jdbc.query("SELECT * FROM categories WHERE user_id=? AND template_key IS NOT NULL ORDER BY created_at,id",this::map,owner);
    }
}
