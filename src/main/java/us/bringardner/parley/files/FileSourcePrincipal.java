package us.bringardner.parley.files;

public class FileSourcePrincipal implements java.io.Serializable {
	private static final long serialVersionUID = 1L;

	private int id;
	private String name="UnKnown";
	
	public FileSourcePrincipal() {
		
	}
	
	public FileSourcePrincipal(FileSourcePrincipal principal) {
		this(principal.getId(),principal.getName());
	}
	
	public FileSourcePrincipal(int id, String name) {
		this.id = id;
		this.name = name;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}
	
	public String toString() {
		return  ""+id+"("+name+")";
	}
	
	
}
