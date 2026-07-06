package com.justnothing.engine.ast.nodes;

import com.justnothing.engine.ast.ASTNode;
import com.justnothing.engine.ast.SourceLocation;
import com.justnothing.engine.ast.visitor.ASTVisitor;

public class ForEachNode extends ASTNode {
    private final Class<?> itemType;
    private final ClassDeclarationNode itemTypeNode;
    private final String itemName;
    private final ASTNode collection;
    private final ASTNode body;
    
    private ForEachNode(Class<?> itemType, ClassDeclarationNode itemTypeNode, String itemName, ASTNode collection, ASTNode body, SourceLocation location) {
        super(location);
        this.itemType = itemType;
        this.itemTypeNode = itemTypeNode;
        this.itemName = itemName;
        this.collection = collection;
        this.body = body;
    }
    
    public Class<?> getItemType() {
        return itemType;
    }
    
    public String getItemName() {
        return itemName;
    }
    
    public ASTNode getCollection() {
        return collection;
    }
    
    public ASTNode getBody() {
        return body;
    }
    
    @Override
    public <T> T accept(ASTVisitor<T> visitor) {
        return visitor.visit(this);
    }
    
    @Override
    public String formatString() {
        return formatString(0);
    }
    
    @Override
    public String formatString(int indent) {
        String sb = indent(indent) + "ForEachNode\n" +
                indent(indent + 1) + "itemType: " + (
                        itemTypeNode == null ?
                                (itemType != null ? itemType.getSimpleName() : "auto")
                                : ("\n" + itemTypeNode.formatString(indent + 1))) + "\n" +
                indent(indent + 1) + "itemName: " + itemName + "\n" +
                indent(indent + 1) + "collection:\n" +
                collection.formatString(indent + 2) + "\n" +
                indent(indent + 1) + "body:\n" +
                body.formatString(indent + 2) + "\n";
        return sb.stripTrailing();
    }

    public static class Builder extends ASTNode.Builder<Builder> {
        private Class<?> itemType;
        private ClassDeclarationNode itemTypeNode;
        private String itemName;
        private ASTNode collection;
        private ASTNode body;

        public Builder itemType(Class<?> itemType) {
            this.itemType = itemType;
            return this;
        }

        public Builder itemTypeNode(ClassDeclarationNode itemType) {
            this.itemTypeNode = itemType;
            return this;
        }

        public Builder itemName(String itemName) {
            this.itemName = itemName;
            return this;
        }

        public Builder collection(ASTNode collection) {
            this.collection = collection;
            return this;
        }

        public Builder body(ASTNode body) {
            this.body = body;
            return this;
        }

        @Override
        public ASTNode build() {
            return new ForEachNode(itemType, itemTypeNode, itemName, collection, body, location);
        }
    }
}
