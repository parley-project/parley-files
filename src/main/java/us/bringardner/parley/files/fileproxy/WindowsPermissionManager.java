package us.bringardner.parley.files.fileproxy;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import us.bringardner.parley.files.fileproxy.FileProxy.PermissionManager;

public class WindowsPermissionManager implements PermissionManager{

	public static final String GROUP_SID_PROPERTY = "GroupSid";
	public static final String OTHER_SID_PROPERTY = "OtherSid";
	
	private static volatile String groupSid;
	private static volatile String otherSid;
	
	public static String getGroupSid() {
		if( groupSid == null ) {
			synchronized (WindowsPermissionManager.class) {
				if( groupSid == null ) {
					groupSid= System.getProperty(GROUP_SID_PROPERTY, "Users");
				}
			}
		}
		
		return groupSid;
	}
	
	public static String getOtherSid() {
		if( otherSid == null ) {
			synchronized (WindowsPermissionManager.class) {
				if( otherSid == null ) {
					otherSid= System.getProperty(OTHER_SID_PROPERTY, "Other");
				}
			}
		}
		
		return otherSid;
	}
	
	
	private File target ;

	public WindowsPermissionManager(File file) {
		target = file;
		
	}
	
	public UserPrincipal getGroupPrincipal() throws IOException {
		return target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(getGroupSid());			
	}

	public UserPrincipal getOtherPrincipal() throws IOException {
		return target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(getOtherSid());			
	}

	private boolean isAllowed(UserPrincipal user,AclEntryPermission permission) throws IOException {
		boolean ret = false;
		
		AclFileAttributeView view = Files.getFileAttributeView(target.toPath(), AclFileAttributeView.class);
		List<AclEntry> acl = view.getAcl();

		for(AclEntry e : acl) {
			if(e.principal().equals(user)) {
				for(AclEntryPermission p : e.permissions()) { 
					if( p.equals(permission)) {
						if( e.type() == AclEntryType.DENY) {
							return false;
						}
						if( e.type() == AclEntryType.ALLOW) {
							ret = true;
						}
					}
				} 
			} 
		}

		return ret;
	}
	
	private boolean isOtherAllowed(AclEntryPermission permission) throws IOException {
		boolean ret = false;
		GroupPrincipal other = target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(getOtherSid());		
		ret = isAllowed(other, permission);
		return ret;
	}

	private boolean isGroupAllowed( AclEntryPermission permission) throws IOException {
		boolean ret = false;
		UserPrincipal group = target.toPath().getFileSystem().getUserPrincipalLookupService().lookupPrincipalByGroupName(getGroupSid());		
		ret = isAllowed(group, permission);

		return ret;
	}

	/**
	 * Windows permissions are an ACL, not rwx bits, and editing the ACL here is not safe: writing
	 * the list back made every entry explicit and stopped the folder's inheritance, and the entry
	 * made for the owner held only the one permission asked for, so a user lost the right to
	 * change (or delete) what was in their own folders. So this changes nothing. It answers, as
	 * java.io.File's setters do on Windows, whether the permission is as asked for already.
	 */
	private boolean setPermission(UserPrincipal user,AclEntryPermission perm,boolean value) throws IOException {
		return isAllowed(user, perm) == value;
	}

	public boolean setOwnerReadable(boolean value) throws IOException {
		//  as java.io.File on Windows: a file is readable or it is not; this does not change that
		return target.setReadable(value, true);
	}

	public boolean setOwnerWritable(boolean value) throws IOException {
		return target.setWritable(value, true);
	}

	

	public boolean setGroupReadable(boolean value) throws IOException {
		boolean ret =setPermission(getGroupPrincipal(),AclEntryPermission.READ_DATA, value);
		return ret;
	}

	public boolean setGroupWritable(boolean value) throws IOException {
		boolean ret =setPermission(getGroupPrincipal(),AclEntryPermission.WRITE_DATA, value);
		return ret;
	}

	
	public boolean setOtherReadable(boolean value) throws IOException {
		boolean ret =setPermission(getOtherPrincipal(),AclEntryPermission.READ_DATA, value);
		return ret;
	}

	public boolean setOtherWritable(boolean value) throws IOException {
		boolean ret =setPermission(getOtherPrincipal(),AclEntryPermission.WRITE_DATA, value);
		return ret;
	}


	@Override
	public boolean canRead() throws IOException {
		return Files.isReadable(target.toPath());
	}

	@Override
	public boolean canWrite() throws IOException {
		//  As java.io.File: a file with the read-only attribute is not writable. The ACL is asked
		//  as well, so this never says yes to a write that would be refused.
		return target.canWrite() && Files.isWritable(target.toPath());
	}

	@Override
	public boolean canExecute() throws IOException {
		return Files.isExecutable(target.toPath());
	}

	//  The owner's bits are what the user can do: the system's answer, which is what the setters
	//  above act on. (They were read from an ACL entry naming the owner, which an ordinary file
	//  does not have: the access comes from inherited group entries. So a file showed ---, and
	//  copying its "permissions" onto another file made that one read-only.)

	@Override
	public boolean canOwnerExecute() throws IOException {
		return canExecute();
	}

	@Override
	public boolean canOwnerRead() throws IOException {
		return canRead();
	}

	@Override
	public boolean canOwnerWrite() throws IOException {
		return canWrite();
	}

	@Override
	public boolean canGroupRead() throws IOException {
		return isGroupAllowed( AclEntryPermission.READ_DATA);
	}

	@Override
	public boolean canGroupWrite() throws IOException {
		return isGroupAllowed( AclEntryPermission.WRITE_DATA);
	}

	@Override
	public boolean canGroupExecute() throws IOException {
		return isGroupAllowed( AclEntryPermission.EXECUTE);
	}

	@Override
	public boolean canOtherRead() throws IOException {
		return isOtherAllowed( AclEntryPermission.READ_DATA);
	}

	@Override
	public boolean canOtherWrite() throws IOException {
		return isOtherAllowed( AclEntryPermission.WRITE_DATA);
	}



	@Override
	public boolean canOtherExecute() throws IOException {
		return isOtherAllowed( AclEntryPermission.EXECUTE);
	}

	@Override
	public boolean setExecutable(boolean b, boolean ownerOnly) throws IOException {
		//  As java.io.File does on Windows (it answers "enable" and changes nothing). The owner,
		//  group and other setters edit the ACL.
		return target.setExecutable(b, ownerOnly);
	}

	@Override
	public boolean setReadable(boolean b, boolean ownerOnly) throws IOException {
		//  As java.io.File does on Windows (see setExecutable).
		return target.setReadable(b, ownerOnly);
	}

	@Override
	public boolean setWritable(boolean b, boolean ownerOnly) throws IOException {
		//  As java.io.File does on Windows: the read-only attribute, which is for everyone. (Removing
		//  the owner's ACL entry leaves the file writable through the inherited entries.) The
		//  owner, group and other setters below still edit the ACL.
		return target.setWritable(b, ownerOnly);
	}

	@Override
	public boolean setExecutable(boolean b) throws IOException {
		return target.setExecutable(b);
	}

	@Override
	public boolean setReadable(boolean b) throws IOException {
		return target.setReadable(b);
	}

	@Override
	public boolean setWritable(boolean b) throws IOException {
		return target.setWritable(b);
	}

	@Override
	public boolean setGroupExecutable(boolean b) throws IOException {
		return setPermission(getGroupPrincipal(),AclEntryPermission.EXECUTE, b);
	}

	@Override
	public boolean setOwnerExecutable(boolean b) throws IOException {
		return target.setExecutable(b, true);
	}

	@Override
	public boolean setOtherExecutable(boolean b) throws IOException {
		return setPermission(getOtherPrincipal(),AclEntryPermission.EXECUTE, b);
	}

	@Override
	public boolean setLastAccessTime(long time) throws IOException {
		BasicFileAttributeView view = Files.getFileAttributeView(target.toPath(), BasicFileAttributeView.class);
		view.setTimes(null, FileTime.fromMillis(time),null);
		return true;

	}

	@Override
	public boolean setCreateTime(long time) throws IOException {
		BasicFileAttributeView view = Files.getFileAttributeView(target.toPath(), BasicFileAttributeView.class);
		view.setTimes(null, null, FileTime.fromMillis(time));
		return true;
	}

	@Override
	public boolean setGroup(GroupPrincipal group) throws IOException {
		//  Not done: it meant editing the ACL (see setPermission)
		return false;
	}

}
